package com.skillmesh.api.invite;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.common.CursorPage;
import com.skillmesh.api.invite.dto.AcceptInvitationResponse;
import com.skillmesh.api.invite.dto.CreateInvitationRequest;
import com.skillmesh.api.invite.dto.InvitationResponse;
import com.skillmesh.api.invite.dto.InvitationSummary;
import com.skillmesh.api.org.OrganizationEntity;
import com.skillmesh.api.org.TeamEntity;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 邀请端点（设计文档 §10 团队管理）。 */
@RestController
@RequestMapping("/api/v1")
public class InvitationController {

    private final InvitationService invitations;
    private final CurrentUserService currentUserService;

    public InvitationController(InvitationService invitations, CurrentUserService currentUserService) {
        this.invitations = invitations;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/organizations/{organizationId}/invitations")
    public ResponseEntity<InvitationResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID organizationId,
            @Valid @RequestBody(required = false) CreateInvitationRequest req) {
        CurrentUser user = currentUserService.current(jwt);
        // 没给请求体 = 组织级邀请：team_role 留 null（服务层按「没给团队」判断，
        // 存库那步再兜成 member——DDL 里 team_role 是 NOT NULL）。
        // 这里不能填 "member"：给了团队角色就必须给团队，否则 controller 的默认值
        // 会撞上自己刚加的规则。
        CreateInvitationRequest body = req == null
            ? new CreateInvitationRequest(null, "member", null, null) : req;
        InvitationService.Created created = invitations.create(user, organizationId,
            body.team_id(), body.org_role(), body.team_role(), body.subject());
        InvitationEntity entity = created.invitation();
        OrganizationEntity org = invitations.organizationOf(entity);
        TeamEntity team = invitations.teamOf(entity);
        return ResponseEntity.created(URI.create("/api/v1/invitations/" + created.id()))
            .body(new InvitationResponse(entity.getId(),
                org == null ? null : org.getSlug(),
                org == null ? null : org.getName(),
                team == null ? null : team.getSlug(),
                team == null ? null : team.getName(),
                entity.getOrgRole(), entity.getTeamRole(), entity.getExpiresAt(),
                invitations.inviteUrl(created.token()), created.token()));
    }

    @GetMapping("/organizations/{organizationId}/invitations")
    public CursorPage<InvitationSummary> list(@AuthenticationPrincipal Jwt jwt,
                               @PathVariable UUID organizationId,
                               @RequestParam(required = false) Integer limit,
                               @RequestParam(required = false) String cursor) {
        CurrentUser user = currentUserService.current(jwt);
        invitations.requireOrgMember(user, organizationId);
        return invitations.listSummaries(organizationId, limit, cursor);
    }

    @DeleteMapping("/organizations/{organizationId}/invitations/{invitationId}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable UUID organizationId,
                                       @PathVariable UUID invitationId) {
        invitations.revoke(currentUserService.current(jwt), organizationId, invitationId);
        return ResponseEntity.noContent().build();
    }

    /** 公开：没登录的人也要看得懂这份邀请是什么。 */
    @GetMapping("/invitations/{token}")
    public InvitationSummary publicView(@PathVariable String token) {
        return invitations.summarize(invitations.summary(token));
    }

    @PostMapping("/invitations/{token}/accept")
    public AcceptInvitationResponse accept(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable String token) {
        InvitationService.Accepted accepted = invitations.accept(currentUserService.current(jwt), token);
        return new AcceptInvitationResponse(accepted.invitationId(), accepted.organizationId(),
            accepted.teamId(), accepted.orgRole(), accepted.teamRole(), "已加入");
    }
}
