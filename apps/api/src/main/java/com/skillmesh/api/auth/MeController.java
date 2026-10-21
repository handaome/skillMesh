package com.skillmesh.api.auth;

import com.skillmesh.api.auth.dto.MeResponse;
import com.skillmesh.api.org.OrgService;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MeController {

    private final CurrentUserService currentUserService;
    private final OrgService orgs;

    public MeController(CurrentUserService currentUserService, OrgService orgs) {
        this.currentUserService = currentUserService;
        this.orgs = orgs;
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser user = currentUserService.current(jwt);
        // 组织列表带着我的角色——CLI 靠它判断能不能往某个团队发东西
        List<MeResponse.OrgMembership> memberships = orgs.myOrganizations(user.id()).stream()
            .map(membership -> new MeResponse.OrgMembership(membership.id(),
                membership.slug(), membership.name(), membership.role()))
            .toList();
        return new MeResponse(
            new MeResponse.User(user.id(), user.displayName(), user.email(), "active",
                user.subject()),
            memberships);
    }
}
