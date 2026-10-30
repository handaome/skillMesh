package com.skillmesh.api.org;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.org.dto.MemberResponse;
import com.skillmesh.api.org.dto.MemberUpdateRequest;
import com.skillmesh.api.org.dto.OrgPermissionsResponse;
import com.skillmesh.api.org.dto.RoleRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 组织与团队管理（设计文档 §10 团队管理）。 */
@RestController
@RequestMapping("/api/v1")
public class OrgController {

    private final OrgService orgs;
    private final CurrentUserService currentUserService;
    private final AuthorizationService auth;

    public OrgController(OrgService orgs, CurrentUserService currentUserService,
                          AuthorizationService auth) {
        this.orgs = orgs;
        this.currentUserService = currentUserService;
        this.auth = auth;
    }

    @PostMapping("/organizations")
    public ResponseEntity<Map<String, Object>> createOrganization(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateOrganizationRequest req) {
        OrganizationEntity org = orgs.createOrganization(
            currentUserService.current(jwt), req.slug(), req.name());
        return ResponseEntity.created(URI.create("/api/v1/organizations/" + org.getId()))
            .body(toResponse(org));
    }

    @PostMapping("/organizations/{organizationId}/teams")
    public ResponseEntity<Map<String, Object>> createTeam(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID organizationId,
            @Valid @RequestBody CreateTeamRequest req) {
        TeamEntity team = orgs.createTeam(currentUserService.current(jwt), organizationId,
            req.slug(), req.name(), req.description());
        return ResponseEntity.created(URI.create("/api/v1/teams/" + team.getId()))
            .body(toResponse(team));
    }

    @PostMapping("/teams/{teamId}/members")
    public ResponseEntity<Void> addTeamMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID teamId,
            @Valid @RequestBody AddMemberRequest req) {
        orgs.addTeamMember(currentUserService.current(jwt), teamId,
            UUID.fromString(req.user_id()), req.role());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/organizations/{organizationId}/teams")
    public List<Map<String, Object>> listTeams(@AuthenticationPrincipal Jwt jwt,
                                              @PathVariable UUID organizationId) {
        CurrentUser user = currentUserService.current(jwt);
        if (!auth.hasOrgPermission(user, Permission.ORG_VIEW_MEMBERS, organizationId)) {
            throw ApiException.notFound("组织");
        }
        return orgs.teamsOf(organizationId).stream().map(OrgController::toResponse).toList();
    }

    @GetMapping("/organizations/{organizationId}/members")
    public List<MemberResponse> listOrgMembers(@AuthenticationPrincipal Jwt jwt,
                                               @PathVariable UUID organizationId) {
        CurrentUser user = currentUserService.current(jwt);
        if (!auth.hasOrgPermission(user, Permission.ORG_VIEW_MEMBERS, organizationId)) {
            throw ApiException.notFound("组织");
        }
        return orgs.orgMembers(organizationId);
    }

    /**
     * 我在这个组织下的权限清单。
     *
     * 前端按它决定按钮显隐：没有的权限点了也是 403，不如别显示。
     * 看的是自己的权限，不需要额外的权限校验。
     */
    @GetMapping("/organizations/{organizationId}/permissions")
    public OrgPermissionsResponse permissions(@AuthenticationPrincipal Jwt jwt,
                                              @PathVariable UUID organizationId) {
        return orgs.permissionsOf(currentUserService.current(jwt), organizationId);
    }

    @GetMapping("/organizations/{organizationId}/teams/{teamId}/members")
    public List<MemberResponse> listTeamMembers(@AuthenticationPrincipal Jwt jwt,
                                                @PathVariable UUID organizationId,
                                                @PathVariable UUID teamId) {
        CurrentUser user = currentUserService.current(jwt);
        // 团队必须在这个组织里——否则换个组织 id 就能看别的团队的成员。
        orgs.requireTeamInOrg(organizationId, teamId);
        if (!auth.hasTeamPermission(user, Permission.TEAM_VIEW_MEMBERS, teamId)) {
            throw ApiException.notFound("团队");
        }
        return orgs.teamMembers(teamId);
    }

    @PatchMapping("/organizations/{organizationId}/members/{userId}")
    public ResponseEntity<Void> updateOrgMember(@AuthenticationPrincipal Jwt jwt,
                                                 @PathVariable UUID organizationId,
                                                 @PathVariable UUID userId,
                                                 @RequestBody MemberUpdateRequest req) {
        // 角色与账号状态都能改，都能不给——至少要给一项，服务层才不报错。
        orgs.updateOrgMember(currentUserService.current(jwt), organizationId, userId,
            req.role(), req.status());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/organizations/{organizationId}/teams/{teamId}/members/{userId}")
    public ResponseEntity<Void> updateTeamMember(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable UUID organizationId,
                                                  @PathVariable UUID teamId,
                                                  @PathVariable UUID userId,
                                                  @Valid @RequestBody RoleRequest req) {
        // 写路径也要校验团队属于该组织——GET 校验了、写入不校验，
        // 同一资源路径会给出不一致的答案。
        orgs.requireTeamInOrg(organizationId, teamId);
        orgs.updateTeamRole(currentUserService.current(jwt), teamId, userId, req.role());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/organizations/{organizationId}/teams/{teamId}/members/{userId}")
    public ResponseEntity<Void> removeTeamMember(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable UUID organizationId,
                                                  @PathVariable UUID teamId,
                                                  @PathVariable UUID userId) {
        orgs.requireTeamInOrg(organizationId, teamId);
        orgs.removeTeamMember(currentUserService.current(jwt), teamId, userId);
        return ResponseEntity.noContent().build();
    }

    private static Map<String, Object> toResponse(OrganizationEntity org) {
        return Map.of("id", org.getId().toString(), "slug", org.getSlug(),
            "name", org.getName(), "status", org.getStatus());
    }

    private static Map<String, Object> toResponse(TeamEntity team) {
        return Map.of("id", team.getId().toString(), "organization_id",
            team.getOrganizationId().toString(), "slug", team.getSlug(),
            "name", team.getName(), "status", team.getStatus());
    }

    public record CreateOrganizationRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,63}$") String slug,
        @NotBlank @Size(max = 120) String name) {}

    public record CreateTeamRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,63}$") String slug,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 1000) String description) {}

    public record AddMemberRequest(
        @NotBlank String user_id,
        @NotBlank String role) {}
}
