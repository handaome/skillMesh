package com.skillmesh.api.audit;

import com.skillmesh.api.audit.dto.ReportAuditEventRequest;
import com.skillmesh.api.auth.CurrentUserService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户端上报的审计事件（详细设计 §12）。
 *
 * 只接受少量客户端自己知道的动作：特权事件（发布、撤回、权限变更）由服务端自己记，
 * 客户端报上来的一律忽略，免得出现两套互相矛盾的流水。
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditReportController {

    /** 允许客户端上报的动作。别的 action 直接 400。 */
    private static final Set<String> ALLOWED = Set.of(
        "install.completed", "install.failed", "install.upgraded", "install.rolled_back",
        "uninstall.completed", "sync.conflict_reported", "sync.retried",
        "source.policy_changed");

    private final AuditService audit;
    private final CurrentUserService currentUserService;

    public AuditReportController(AuditService audit, CurrentUserService currentUserService) {
        this.audit = audit;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> report(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ReportAuditEventRequest req) {
        if (!ALLOWED.contains(req.action())) {
            throw new com.skillmesh.api.common.ApiException(400,
                com.skillmesh.api.common.ErrorCodes.VALIDATION_FAILED,
                "客户端不能上报这个动作：" + req.action() + "（允许：" + String.join(", ", ALLOWED) + "）");
        }
        audit.record(currentUserService.current(jwt), req.action(),
            req.target_type() == null ? "skill" : req.target_type(), req.target_id(),
            req.outcome() == null ? "success" : req.outcome(), req.details());
        return ResponseEntity.created(URI.create("/api/v1/audit/events"))
            .body(Map.of("accepted", true, "action", req.action()));
    }
}
