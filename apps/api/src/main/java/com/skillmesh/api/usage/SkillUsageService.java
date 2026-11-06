package com.skillmesh.api.usage;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.CursorCodec;
import com.skillmesh.api.common.CursorPage;
import com.skillmesh.api.usage.dto.ReportSkillUsageRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Skill 调用统计（设计文档 §11 usages）。
 *
 * 只记事实：谁在什么时候调用了哪个 Skill。装没装、有没有权限，是另一回事。
 */
@Service
public class SkillUsageService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_STATS_LIMIT = 50;
    private static final int MAX_STATS_LIMIT = 200;
    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 365;
    private static final int ARGS_PREVIEW_LIMIT = 1000;

    private final SkillCallRecordRepository records;

    public SkillUsageService(SkillCallRecordRepository records) {
        this.records = records;
    }

    /**
     * 记一次调用。
     *
     * 返回 false 表示这条已经报过了（幂等键撞上），不是错误。
     */
    @Transactional
    public boolean report(CurrentUser user, ReportSkillUsageRequest req) {
        String skillName = req.skill().trim();
        String eventId = req.event_id() == null || req.event_id().isBlank()
            ? UUID.randomUUID().toString()
            : req.event_id().trim();
        SkillRef ref = findSkill(user, skillName);
        return records.insertIgnore(eventId, OffsetDateTime.now(ZoneOffset.UTC),
            user.id(), ref == null ? null : ref.id(), ref == null ? null : ref.organizationId(),
            skillName, agentOf(req), blankToNull(req.project()), previewOf(req.args())) > 0;
    }

    /** 统计：按 skill 名聚合的调用次数。 */
    @Transactional(readOnly = true)
    public List<SkillCallRecordRepository.SkillUsageStatRow> stats(CurrentUser user, Integer days,
                                                                    String skill, Integer limit) {
        int window = days == null ? DEFAULT_DAYS : Math.clamp(days, 1, MAX_DAYS);
        int size = limit == null ? DEFAULT_STATS_LIMIT : Math.clamp(limit, 1, MAX_STATS_LIMIT);
        // 过滤走前缀匹配：不过滤时用 '%'，不用 null 判断——原生 SQL 里传 null 参数容易踩类型推断。
        String prefix = skill == null || skill.isBlank() ? "%" : skill.trim() + "%";
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(window);
        return records.stats(user.id(), since, prefix, size);
    }

    /** 汇总：总调用次数、涉及多少个 skill、涉及多少人（全局去重）。 */
    @Transactional(readOnly = true)
    public Summary summary(CurrentUser user, Integer days, String skill) {
        int window = days == null ? DEFAULT_DAYS : Math.clamp(days, 1, MAX_DAYS);
        String prefix = skill == null || skill.isBlank() ? "%" : skill.trim() + "%";
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(window);
        List<SkillCallRecordRepository.SkillUsageSummaryRow> rows =
            records.summary(user.id(), since, prefix);
        if (rows.isEmpty()) {
            return new Summary(0, 0, 0);
        }
        SkillCallRecordRepository.SkillUsageSummaryRow row = rows.get(0);
        return new Summary(row.getTotalCalls(), row.getSkillCount(), row.getUserCount());
    }

    /** 明细流水，按时间倒序往下翻。 */
    @Transactional(readOnly = true)
    public CursorPage<SkillCallRecordEntity> recent(CurrentUser user, Integer limit, String cursor) {
        int size = limit == null ? DEFAULT_PAGE_SIZE : Math.clamp(limit, 1, MAX_PAGE_SIZE);
        CursorCodec.NumericCursor decoded = CursorCodec.decodeNumericOrEmpty(cursor);
        List<SkillCallRecordEntity> rows =
            records.findVisible(user.id(), decoded.time(), decoded.id(), size + 1);
        return CursorPage.of(rows, size,
            event -> CursorCodec.encodeNumeric(event.getOccurredAt(), event.getId()));
    }

    /**
     * 把本地 skill 名对到云端 Skill。
     *
     * 对不上是常事：本地装的 skill 不一定发到过云端，插件带的 skill 名还带命名空间
     * （如 pm-toolkit:brainstorm）。对不上就只按名字统计，不硬挂。
     */
    private SkillRef findSkill(CurrentUser user, String skillName) {
        SkillCallRecordRepository.SkillRefRow row = firstMatch(
            records.findVisibleBySlug(user.id(), skillName));
        if (row == null) {
            int colon = skillName.lastIndexOf(':');
            if (colon > 0 && colon < skillName.length() - 1) {
                row = firstMatch(records.findVisibleBySlug(user.id(), skillName.substring(colon + 1)));
            }
        }
        return row == null ? null : new SkillRef(row.getId(), row.getOrganizationId());
    }

    private static SkillCallRecordRepository.SkillRefRow firstMatch(
            List<SkillCallRecordRepository.SkillRefRow> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String agentOf(ReportSkillUsageRequest req) {
        return req.agent() == null || req.agent().isBlank() ? "claude-code" : req.agent().trim();
    }

    /** 参数只留开头一段：里面可能夹着文件内容，不适合整段落库。 */
    private static String previewOf(String args) {
        if (args == null || args.isBlank()) {
            return null;
        }
        String oneLine = args.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= ARGS_PREVIEW_LIMIT
            ? oneLine
            : oneLine.substring(0, ARGS_PREVIEW_LIMIT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 匹配到的云端 Skill。 */
    private record SkillRef(UUID id, UUID organizationId) {
    }

    /** 汇总口径。 */
    public record Summary(long totalCalls, long skillCount, long userCount) {
    }
}
