package com.skillmesh.api.audit;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SkillService;
import java.time.OffsetDateTime;
import java.util.List;
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
    private final SkillRepository skills;
    private final SkillService skillService;
    private final CurrentUserService currentUserService;

    public AuditController(AuditService audit, SkillRepository skills,
                           CurrentUserService currentUserService,
                           SkillService skillService) {
        this.audit = audit;
        this.skills = skills;
        this.currentUserService = currentUserService;
        this.skillService = skillService;
    }

    /**
     * 我可见的审计流水（详细设计 §12 的 /audit-events）。
     * 只返回我可见的 Skill 的事件——审计不是所有人都能翻的。
     */
    @GetMapping("/audit-events")
    public List<EventResponse> recent(@AuthenticationPrincipal Jwt jwt,
                                      @RequestParam(required = false) String action,
                                      @RequestParam(defaultValue = "50") int limit) {
        CurrentUser user = currentUserService.current(jwt);
        return audit.recentVisible(user, action, limit).stream()
            .map(EventResponse::of)
            .toList();
    }

    @GetMapping("/skills/{skillId}/audit")
    public List<EventResponse> forSkill(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable UUID skillId,
                                        @RequestParam(defaultValue = "50") int limit) {
        var skill = skills.findById(skillId).orElseThrow();
        if (!skillService.canView(currentUserService.current(jwt), skill)) {
            throw ApiException.notFound("Skill");
        }
        int capped = Math.clamp(limit, 1, 200);
        return audit.recentForSkill(skillId, capped).stream()
            .map(EventResponse::of)
            .toList();
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
