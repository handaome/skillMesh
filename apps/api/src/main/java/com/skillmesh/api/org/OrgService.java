package com.skillmesh.api.org;

import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组织、团队与成员（设计文档 §10 团队管理、§11 数据模型）。
 *
 * 角色分两层：组织成员（owner/admin/publisher/reviewer/member/viewer）和
 * 团队成员（admin/publisher/reviewer/member/viewer）。看得见和管得了是两回事，
 * 所以两个表都要查。
 */
@Service
public class OrgService {

    private final OrgRepository organizations;
    private final TeamRepository teams;
    private final OrgMembershipRepository orgMembers;
    private final TeamMembershipRepository teamMembers;
    private final AuditService audit;

    public OrgService(OrgRepository organizations, TeamRepository teams,
                      OrgMembershipRepository orgMembers, TeamMembershipRepository teamMembers,
                      AuditService audit) {
        this.organizations = organizations;
        this.teams = teams;
        this.orgMembers = orgMembers;
        this.teamMembers = teamMembers;
        this.audit = audit;
    }

    @Transactional
    public OrganizationEntity createOrganization(CurrentUser user, String slug, String name) {
        if (organizations.findBySlug(slug).isPresent()) {
            throw new ApiException(409, ErrorCodes.SLUG_TAKEN, "组织标识已被占用：" + slug);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OrganizationEntity org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setSlug(slug);
        org.setName(name);
        org.setStatus("active");
        org.setPolicyJson(Map.of());
        org.setCreatedBy(user.id());
        org.setCreatedAt(now);
        org.setUpdatedAt(now);
        organizations.save(org);

        MembershipEntities.OrgMembership owner = new MembershipEntities.OrgMembership();
        owner.setId(new MembershipEntities.OrgKey(org.getId(), user.id()));
        owner.setRole("owner");
        owner.setJoinedAt(now);
        orgMembers.save(owner);

        audit.record(user, "organization.create", "organization", org.getId(), "success",
            Map.of("slug", slug));
        return org;
    }

    @Transactional
    public TeamEntity createTeam(CurrentUser user, UUID organizationId, String slug, String name,
                                String description) {
        requireOrgRole(user, organizationId, Set.of("owner", "admin"));
        if (teams.findByOrganizationIdAndSlug(organizationId, slug).isPresent()) {
            throw new ApiException(409, ErrorCodes.SLUG_TAKEN, "团队标识已被占用：" + slug);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        TeamEntity team = new TeamEntity();
        team.setId(UUID.randomUUID());
        team.setOrganizationId(organizationId);
        team.setSlug(slug);
        team.setName(name);
        team.setDescription(description);
        team.setStatus("active");
        team.setCreatedAt(now);
        team.setUpdatedAt(now);
        teams.save(team);
        audit.record(user, "team.create", "team", team.getId(), "success", Map.of("slug", slug));
        return team;
    }

    @Transactional
    public void addOrgMember(CurrentUser actor, UUID organizationId, UUID memberId, String role) {
        requireOrgRole(actor, organizationId, Set.of("owner", "admin"));
        requireRole(role, Set.of("owner", "admin", "publisher", "reviewer", "member", "viewer"));
        MembershipEntities.OrgMembership membership = new MembershipEntities.OrgMembership();
        membership.setId(new MembershipEntities.OrgKey(organizationId, memberId));
        membership.setRole(role);
        membership.setJoinedAt(OffsetDateTime.now(ZoneOffset.UTC));
        orgMembers.save(membership);
        audit.record(actor, "organization.membership.grant", "organization", organizationId,
            "success", Map.of("user_id", memberId.toString(), "role", role));
    }

    @Transactional
    public void addTeamMember(CurrentUser actor, UUID teamId, UUID memberId, String role) {
        TeamEntity team = teams.findById(teamId)
            .orElseThrow(() -> ApiException.notFound("团队"));
        // 组织 owner/admin 或团队 admin 都能加成员
        boolean orgAdmin = hasOrgRole(actor, team.getOrganizationId(), Set.of("owner", "admin"));
        boolean teamAdmin = teamMembers.findById(
                new MembershipEntities.TeamKey(teamId, actor.id()))
            .map(MembershipEntities.TeamMembership::getRole)
            .filter("admin"::equals)
            .isPresent();
        if (!orgAdmin && !teamAdmin) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "只有组织或团队管理员能加成员");
        }
        requireRole(role, Set.of("admin", "publisher", "reviewer", "member", "viewer"));
        MembershipEntities.TeamMembership membership = new MembershipEntities.TeamMembership();
        membership.setId(new MembershipEntities.TeamKey(teamId, memberId));
        membership.setRole(role);
        membership.setJoinedAt(OffsetDateTime.now(ZoneOffset.UTC));
        teamMembers.save(membership);
        audit.record(actor, "team.membership.grant", "team", teamId, "success",
            Map.of("user_id", memberId.toString(), "role", role));
    }

    /** 我是不是这个组织的成员？团队/组织可见性都靠它。 */
    public boolean isOrgMember(UUID organizationId, UUID userId) {
        return orgMembers.findById(new MembershipEntities.OrgKey(organizationId, userId)).isPresent();
    }

    public boolean isTeamMember(UUID teamId, UUID userId) {
        return teamMembers.findById(new MembershipEntities.TeamKey(teamId, userId)).isPresent();
    }

    /** /me 要返回的组织列表。 */
    public List<Membership> myOrganizations(UUID userId) {
        List<Membership> result = new ArrayList<>();
        for (MembershipEntities.OrgMembership membership : orgMembers.findByIdUserId(userId)) {
            OrganizationEntity org = organizations.findById(membership.getId().getOrganizationId())
                .orElse(null);
            if (org == null) {
                continue;
            }
            result.add(new Membership(org.getId(), org.getSlug(), org.getName(),
                membership.getRole()));
        }
        return result;
    }

    public List<UUID> myOrganizationIds(UUID userId) {
        return orgMembers.findByIdUserId(userId).stream()
            .map(membership -> membership.getId().getOrganizationId())
            .toList();
    }

    public List<UUID> myTeamIds(UUID userId) {
        return teamMembers.findByIdUserId(userId).stream()
            .map(membership -> membership.getId().getTeamId())
            .toList();
    }

    public List<TeamEntity> teamsOf(UUID organizationId) {
        return teams.findByOrganizationIdOrderByCreatedAtAsc(organizationId);
    }

    private void requireOrgRole(CurrentUser user, UUID organizationId, Set<String> allowed) {
        if (!hasOrgRole(user, organizationId, allowed)) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "组织权限不足");
        }
    }

    private boolean hasOrgRole(CurrentUser user, UUID organizationId, Set<String> allowed) {
        return orgMembers.findById(new MembershipEntities.OrgKey(organizationId, user.id()))
            .map(MembershipEntities.OrgMembership::getRole)
            .filter(allowed::contains)
            .isPresent();
    }

    private void requireRole(String role, Set<String> allowed) {
        if (role == null || !allowed.contains(role)) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "角色只能是 " + String.join(" / ", allowed));
        }
    }

    /** 我在某个组织里的身份。 */
    public record Membership(UUID id, String slug, String name, String role) {}
}
