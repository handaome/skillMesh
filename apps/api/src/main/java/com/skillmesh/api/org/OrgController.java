package com.skillmesh.api.org;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.common.ApiException;
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
import org.springframework.web.bind.annotation.GetMapping;
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

    public OrgController(OrgService orgs, CurrentUserService currentUserService) {
        this.orgs = orgs;
        this.currentUserService = currentUserService;
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
        if (!orgs.isOrgMember(organizationId, user.id())) {
            throw ApiException.notFound("组织");
        }
        return orgs.teamsOf(organizationId).stream().map(OrgController::toResponse).toList();
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
