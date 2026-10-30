package com.skillmesh.api.backup;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmesh.api.backup.BackupRestoreService.ForeignKey;
import com.skillmesh.api.backup.BackupRestoreService.RestoreResult;
import com.skillmesh.api.backup.BackupService.Manifest;
import com.skillmesh.api.backup.BackupService.TableBackup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 备份能不能恢复回去——设计文档 §14 要的「验证恢复流程」。
 *
 * 光校验备份自己没坏不算数：行插不回去、顺序不对、数组列插不进去，
 * 都要真恢复一次才知道。这里把整份备份恢复进一个探针 schema，
 * 再逐张表对账。
 *
 * 探针 schema 是照着 public 的表结构复制出来的空表：
 * 恢复进 public 会把库里现有的东西冲掉，那不是测试该干的事。
 */
@SpringBootTest
class BackupRestoreTest {

    private static final String PROBE = "probe_backup_restore";

    @Autowired
    private BackupService backups;

    @Autowired
    private BackupRestoreService restores;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 导出的备份能恢复回一个空库() throws Exception {
        Path dir = Files.createTempDirectory("skillmesh-backup-");
        try {
            jdbc.execute("drop schema if exists " + PROBE + " cascade");
            jdbc.execute("create schema " + PROBE);
            Manifest manifest = backups.export(dir);
            assertThat(manifest.tables()).isNotEmpty();

            // 探针表：照着 public 的结构复制一份空的。
            for (TableBackup table : manifest.tables()) {
                jdbc.execute("create table " + PROBE + "." + table.table()
                    + " (like public." + table.table() + " including all)");
            }
            // 自增列的默认值指向 public 的序列：留着的话恢复会把那条序列拨动，
            // public 里的新行会跳过一段 id。探针表不需要序列。
            jdbc.query("select table_name, column_name from information_schema.columns "
                + "where table_schema = ? and column_default like 'nextval%'",
                rs -> {
                    jdbc.execute("alter table " + PROBE + "." + rs.getString(1)
                        + " alter column " + rs.getString(2) + " drop default");
                }, PROBE);

            // 只恢复数据库部分：对象那步会把桶里的对象重写一遍，
            // 那是共享环境，不该由一个测试去动。
            RestoreResult result = restores.restoreDatabase(dir, PROBE);

            assertThat(result.rows()).isPositive();
            // 逐张表对账：恢复进去的行数要与备份清单一致。
            for (TableBackup table : manifest.tables()) {
                Integer count = jdbc.queryForObject(
                    "select count(*) from " + PROBE + "." + table.table(), Integer.class);
                assertThat(count)
                    .as("表 %s 的行数", table.table())
                    .isEqualTo((int) table.rows());
            }
            // 内容也要对得上，不只是行数。制品表最实在：一行一个摘要。
            List<String> restored = jdbc.queryForList(
                "select sha256 from " + PROBE + ".artifacts order by sha256", String.class);
            List<String> original = jdbc.queryForList(
                "select sha256 from public.artifacts order by sha256", String.class);
            assertThat(restored).isEqualTo(original);
        } finally {
            jdbc.execute("drop schema if exists " + PROBE + " cascade");
            deleteTree(dir);
        }
    }

    /**
     * 恢复顺序必须把父表排在子表前面。
     *
     * 只读 public 的表结构，不动数据——顺序算错了，恢复时会插不进去，
     * 而插不进去的原因往往是一句看不懂的外键报错。
     */
    @Test
    void 恢复顺序排在父表前面() {
        List<String> names = jdbc.queryForList(
            "select table_name from information_schema.tables "
                + "where table_schema = 'public' and table_type = 'BASE TABLE' order by table_name",
            String.class);
        List<TableBackup> tables = names.stream()
            .map(name -> new TableBackup(name, "db/" + name + ".ndjson", 0, ""))
            .toList();
        Manifest manifest = new Manifest("now", "bucket", tables, List.of());

        List<String> order = restores.restoreOrder("public", manifest);
        assertThat(order).hasSize(names.size());

        Map<String, Integer> position = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            position.put(order.get(i), i);
        }
        for (ForeignKey key : restores.foreignKeys("public")) {
            if (key.deferrable()) {
                // 延后检查的约束（skills.latest_version → skill_versions）不参与排序，
                // 它留到提交时才查。要是在这里断言顺序，就等于要求它有个顺序。
                continue;
            }
            assertThat(position.get(key.parent()))
                .as("%s 必须在 %s 前面", key.parent(), key.child())
                .isLessThan(position.get(key.child()));
        }
    }

    private void deleteTree(Path dir) throws Exception {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // 临时目录：删不掉就留给系统清理。
                }
            });
        }
    }
}
