package com.skillmesh.api.usage;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.common.CursorPage;
import com.skillmesh.api.usage.dto.ReportSkillUsageRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Skill 调用统计（设计文档 §11 usages）。
 *
 * 上报端点给客户端用（Agent 每次真调用了 Skill 就报一条），
 * 查询端点给控制台用：哪些 Skill 真的在用、用了多少次。
 */
@RestController
@RequestMapping("/api/v1/skill-usage")
public class SkillUsageController {

    private final SkillUsageService usage;
    private final CurrentUserService currentUserService;

    public SkillUsageController(SkillUsageService usage, CurrentUserService currentUserService) {
        this.usage = usage;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> report(@AuthenticationPrincipal Jwt jwt,
                                                     @Valid @RequestBody ReportSkillUsageRequest req) {
        CurrentUser user = currentUserService.current(jwt);
        boolean recorded = usage.report(user, req);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(Map.of("accepted", recorded));
    }

    @GetMapping("/stats")
    public List<StatResponse> stats(@AuthenticationPrincipal Jwt jwt,
                                     @RequestParam(required = false) Integer days,
                                     @RequestParam(required = false) String skill,
                                     @RequestParam(required = false) Integer limit) {
        CurrentUser user = currentUserService.current(jwt);
        return usage.stats(user, days, skill, limit).stream().map(StatResponse::of).toList();
    }

    @GetMapping("/summary")
    public SummaryResponse summary(@AuthenticationPrincipal Jwt jwt,
                                    @RequestParam(required = false) Integer days,
                                    @RequestParam(required = false) String skill) {
        CurrentUser user = currentUserService.current(jwt);
        SkillUsageService.Summary summary = usage.summary(user, days, skill);
        return new SummaryResponse(summary.totalCalls(), summary.skillCount(), summary.userCount());
    }

    @GetMapping("/events")
    public CursorPage<EventResponse> recent(@AuthenticationPrincipal Jwt jwt,
                                            @RequestParam(required = false) Integer limit,
                                            @RequestParam(required = false) String cursor) {
        CurrentUser user = currentUserService.current(jwt);
        return usage.recent(user, limit, cursor)
            .map(page -> page.stream().map(EventResponse::of).toList());
    }

    /** 一个 skill 的聚合统计。 */
    public record StatResponse(
            String skill,
            @JsonProperty("call_count") long callCount,
            @JsonProperty("user_count") long userCount,
            @JsonProperty("last_used_at") OffsetDateTime lastUsedAt) {

        static StatResponse of(SkillCallRecordRepository.SkillUsageStatRow row) {
            // 统计行里的时间是 Instant（原生 SQL 的 max() 返回值），这里补回时区。
            Instant lastUsedAt = row.getLastUsedAt();
            return new StatResponse(row.getSkill(), row.getCallCount(), row.getUserCount(),
                lastUsedAt == null ? null : lastUsedAt.atOffset(ZoneOffset.UTC));
        }
    }

    /** 汇总口径。人数是全局去重的：一个人用了多个 skill 只算一个。 */
    public record SummaryResponse(
            @JsonProperty("total_calls") long totalCalls,
            @JsonProperty("skill_count") long skillCount,
            @JsonProperty("user_count") long userCount) {
    }

    /** 一次调用的流水。 */
    public record EventResponse(
            @JsonProperty("occurred_at") OffsetDateTime occurredAt,
            String skill,
            String project,
            @JsonProperty("args_preview") String argsPreview,
            String agent) {

        static EventResponse of(SkillCallRecordEntity record) {
            return new EventResponse(record.getOccurredAt(), record.getSkillName(),
                record.getProjectPath() == null ? "" : record.getProjectPath(),
                record.getArgsPreview() == null ? "" : record.getArgsPreview(),
                record.getAgent());
        }
    }
}
