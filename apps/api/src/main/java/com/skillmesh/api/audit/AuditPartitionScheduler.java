package com.skillmesh.api.audit;

import jakarta.persistence.EntityManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.Session;
import org.hibernate.jdbc.ReturningWork;
import org.hibernate.jdbc.Work;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计表的分区维护：按月分区 + 保留期清理。
 *
 * 审计只写不改，留着的是历史流水。到了保留期要清时，
 * 普通表只能 DELETE 一行行删（还带着索引与 bloat），
 * 分区表直接 DROP 分区——一条语句的事，删完空间也还回去。
 *
 * 定时任务做三件事：
 *   补分区——本月与下月的分区在不在，不在就建；
 *   丢分区——过了保留期的整月分区直接 DROP；
 *   清默认分区——落在默认分区里的过期行删掉。
 *
 * 默认分区是兜底：没覆盖到的月份（更早的老数据、时钟跑偏的将来）先落那儿，
 * 补分区时再搬回对应的月份。审计写不进去比审计写得慢糟得多，
 * 所以宁可让行先落默认分区。
 */
@Component
@ConditionalOnProperty(name = "skillmesh.audit.partition-enabled", havingValue = "true",
    matchIfMissing = true)
public class AuditPartitionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuditPartitionScheduler.class);

    /** 月度分区的表名：audit_events_2026_11。默认分区不按这个格式命名。 */
    private static final Pattern PARTITION = Pattern.compile("^audit_events_(\\d{4})_(\\d{2})$");

    private final EntityManager entityManager;
    private final int retentionMonths;

    public AuditPartitionScheduler(EntityManager entityManager,
            @Value("${skillmesh.audit.retention-months:12}") int retentionMonths) {
        this.entityManager = entityManager;
        this.retentionMonths = retentionMonths;
    }

    /**
     * 间隔用毫秒数而不是 cron 表达式：cron 得按时区解释，
     * 服务器换个时区或夏令时切换，清理时间就悄悄变了。
     */
    @Scheduled(fixedDelayString = "${skillmesh.audit.cleanup-interval-ms:86400000}",
        initialDelayString = "${skillmesh.audit.cleanup-initial-delay-ms:600000}")
    @Transactional
    public void maintain() {
        if (!isPartitioned()) {
            // 库还没迁到分区表（或被人改回去了）：别动它，更别把审计写崩了。
            log.warn("audit_events 还不是分区表，跳过分区维护");
            return;
        }
        YearMonth now = YearMonth.now(ZoneOffset.UTC);
        ensurePartition(now);
        ensurePartition(now.plusMonths(1));
        int dropped = dropExpiredPartitions(now);
        int deleted = deleteExpiredInDefault(now);
        if (dropped > 0 || deleted > 0) {
            log.info("审计清理：丢掉 {} 个过期分区，默认分区里清掉 {} 行", dropped, deleted);
        }
    }

    /** 这个月的分区在不在：不在就建一个。 */
    private void ensurePartition(YearMonth month) {
        String table = partitionName(month);
        if (exists(table)) {
            return;
        }
        OffsetDateTime start = startOf(month);
        OffsetDateTime end = startOf(month.plusMonths(1));
        // 默认分区里落在这个月的行先搬到临时表：默认分区与新区间重叠，
        // 不清掉就建不了分区（PG 报 conflicting key value）。
        stageFromDefault(start, end);
        deleteFromDefault(start, end);
        createPartition(table, start, end);
        // 搬回去：按 occurred_at 路由，正好落进刚建的分区。
        execute("INSERT INTO audit_events SELECT * FROM audit_events_move");
    }

    /** 过了保留期的整月分区直接 DROP。 */
    private int dropExpiredPartitions(YearMonth now) {
        YearMonth cutoff = now.minusMonths(retentionMonths);
        int dropped = 0;
        for (String name : partitions()) {
            YearMonth month = monthOf(name);
            if (month == null || !month.isBefore(cutoff)) {
                continue;
            }
            execute("DROP TABLE IF EXISTS " + name);
            dropped++;
        }
        return dropped;
    }

    /** 默认分区里过了保留期的行删掉（这里没有分区可丢，只能一行行删）。 */
    private int deleteExpiredInDefault(YearMonth now) {
        if (!exists("audit_events_default")) {
            return 0;
        }
        OffsetDateTime cutoff = startOf(now.minusMonths(retentionMonths));
        int[] deleted = {0};
        work(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM audit_events_default WHERE occurred_at < ?")) {
                ps.setObject(1, cutoff);
                deleted[0] = ps.executeUpdate();
            }
        });
        return deleted[0];
    }

    /** 把默认分区里落在某段区间里的行搬到临时表。 */
    private void stageFromDefault(OffsetDateTime start, OffsetDateTime end) {
        work(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    CREATE TEMP TABLE audit_events_move ON COMMIT DROP AS
                    SELECT * FROM audit_events_default
                    WHERE occurred_at >= ? AND occurred_at < ?""")) {
                ps.setObject(1, start);
                ps.setObject(2, end);
                ps.execute();
            }
        });
    }

    private void deleteFromDefault(OffsetDateTime start, OffsetDateTime end) {
        work(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    DELETE FROM audit_events_default
                    WHERE occurred_at >= ? AND occurred_at < ?""")) {
                ps.setObject(1, start);
                ps.setObject(2, end);
                ps.executeUpdate();
            }
        });
    }

    private void createPartition(String table, OffsetDateTime start, OffsetDateTime end) {
        execute("CREATE TABLE " + table + " PARTITION OF audit_events FOR VALUES FROM ('"
            + start + "') TO ('" + end + "')");
    }

    /** audit_events 现在是不是分区表。 */
    private boolean isPartitioned() {
        return query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT relkind = 'p' FROM pg_class WHERE oid = to_regclass('public.audit_events')")) {
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        });
    }

    /** 表在不在。 */
    private boolean exists(String table) {
        return query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT to_regclass(?) IS NOT NULL")) {
                ps.setString(1, "public." + table);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        });
    }

    /** 现有的分区表名（含默认分区）。 */
    private List<String> partitions() {
        return query(connection -> {
            List<String> names = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT c.relname
                    FROM pg_class c
                    JOIN pg_inherits i ON i.inhrelid = c.oid
                    JOIN pg_class p ON p.oid = i.inhparent
                    WHERE p.oid = to_regclass('public.audit_events')
                    ORDER BY c.relname""")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString(1));
                    }
                }
            }
            return names;
        });
    }

    private void execute(String sql) {
        work(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.execute();
            }
        });
    }

    private void work(Work task) {
        entityManager.unwrap(Session.class).doWork(task);
    }

    private <T> T query(ReturningWork<T> task) {
        return entityManager.unwrap(Session.class).doReturningWork(task);
    }

    /** 月度分区的表名。 */
    private static String partitionName(YearMonth month) {
        return String.format("audit_events_%04d_%02d", month.getYear(), month.getMonthValue());
    }

    /** 从分区表名里认月份；认不出来（比如默认分区）返回 null。 */
    private static YearMonth monthOf(String tableName) {
        Matcher matcher = PARTITION.matcher(tableName);
        if (!matcher.matches()) {
            return null;
        }
        return YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    /** 月份的起点，按 UTC 算——分区边界跟审计时间戳的时区口径要一致。 */
    private static OffsetDateTime startOf(YearMonth month) {
        return month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
