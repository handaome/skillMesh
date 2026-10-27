package com.skillmesh.api.audit;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.CursorPage;
import com.skillmesh.api.skill.SkillService;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 审计流水查询。只对能看见这个 Skill 的人开放（设计文档 §10）。 */
@RestController
@RequestMapping("/api/v1")
public class AuditController {

    private final AuditService audit;
    private final SkillService skillService;
    private final CurrentUserService currentUserService;

    public AuditController(AuditService audit, CurrentUserService currentUserService,
                           SkillService skillService) {
        this.audit = audit;
        this.currentUserService = currentUserService;
        this.skillService = skillService;
    }

    /**
     * 我可见的审计流水（详细设计 §12 的 /audit-events）。
     * 只返回我可见的 Skill 的事件——审计不是所有人都能翻的。
     *
     * 带游标翻页：流水只增不减，只给最近一页的话，
     * 「上个月那次改动是谁做的」就再也答不上来了。
     */
    @GetMapping("/audit-events")
    public CursorPage<EventResponse> recent(@AuthenticationPrincipal Jwt jwt,
                            @RequestParam(required = false) String action,
                            @RequestParam(required = false) Integer limit,
                            @RequestParam(required = false) String cursor) {
        CurrentUser user = currentUserService.current(jwt);
        return audit.recentVisible(user, action, limit, cursor)
            .map(page -> page.stream().map(EventResponse::of).toList());
    }

    @GetMapping("/skills/{skillId}/audit")
    public CursorPage<EventResponse> forSkill(@AuthenticationPrincipal Jwt jwt,
                              @PathVariable UUID skillId,
                              @RequestParam(required = false) Integer limit,
                              @RequestParam(required = false) String cursor) {
        skillService.requireVisibleSkill(currentUserService.current(jwt), skillId);
        return audit.recentForSkill(skillId, limit, cursor)
            .map(page -> page.stream().map(EventResponse::of).toList());
    }

    public record EventResponse(
            @JsonProperty("occurred_at") OffsetDateTime occurredAt,
            String action,
            @JsonProperty("target_type") String targetType,
            @JsonProperty("target_id") String targetId,
            String outcome,
            Map<String, Object> details) {

        static EventResponse of(AuditEventEntity event) {
            return new EventResponse(event.getOccurredAt(), event.getAction(),
                event.getTargetType(), event.getTargetId(), event.getOutcome(),
                event.getDetailsJson());
        }
    }
}
