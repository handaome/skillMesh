package com.skillmesh.api.org;

import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.UserEntity;
import com.skillmesh.api.auth.UserRepository;
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
    private final UserRepository users;

    public OrgService(OrgRepository organizations, TeamRepository teams,
                      OrgMembershipRepository orgMembers, TeamMembershipRepository teamMembers,
                      AuditService audit, UserRepository users) {
        this.organizations = organizations;
        this.teams = teams;
        this.orgMembers = orgMembers;
        this.teamMembers = teamMembers;
        this.audit = audit;
        this.users = users;
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
        requireRole(role, Roles.ORG_ROLES);
        requireOwnerToGrantOwner(actor, organizationId, role);
        requireNotLastOwner(organizationId, memberId, role);
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
        requireRole(role, Roles.TEAM_ROLES);
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

    /**
     * 这个人在某个 Skill 所属范围里的最高角色。
     * 团队成员优先于组织成员——同一个人可能两个身份都有，取更近的那个。
     */
    public String roleIn(UUID organizationId, UUID teamId, UUID userId) {
        if (teamId != null) {
            String teamRole = teamMembers
                .findById(new MembershipEntities.TeamKey(teamId, userId))
                .map(MembershipEntities.TeamMembership::getRole)
                .orElse("");
            if (!teamRole.isEmpty()) {
                return teamRole;
            }
        }
        if (organizationId == null) {
            return "";
        }
        return orgMembers.findById(new MembershipEntities.OrgKey(organizationId, userId))
            .map(MembershipEntities.OrgMembership::getRole)
            .orElse("");
    }

    /** 角色是否达到要求（owner > admin > publisher > reviewer > member > viewer）。 */
    public static boolean roleAtLeast(String actual, String required) {
        // List.of 的 indexOf 不接受 null（会抛 NPE），没角色就当不达标。
        if (actual == null || required == null) {
            return false;
        }
        int have = Roles.ORG_RANK.indexOf(actual);
        int need = Roles.ORG_RANK.indexOf(required);
        return have >= 0 && need >= 0 && have <= need;
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

    /** 组织管理员以上才能管成员与邀请。 */
    public void requireOrgAdmin(CurrentUser user, UUID organizationId) {
        requireOrgRole(user, organizationId, Set.of("owner", "admin"));
    }

    /** 团队管理员：组织 owner/admin 或团队 admin 都能管团队成员。 */
    public void requireTeamAdmin(CurrentUser user, UUID teamId) {
        TeamEntity team = teams.findById(teamId).orElseThrow(() -> ApiException.notFound("团队"));
        if (hasOrgRole(user, team.getOrganizationId(), Set.of("owner", "admin"))) {
            return;
        }
        boolean teamAdmin = teamMembers.findById(new MembershipEntities.TeamKey(teamId, user.id()))
            .map(MembershipEntities.TeamMembership::getRole)
            .filter("admin"::equals)
            .isPresent();
        if (!teamAdmin) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "只有组织或团队管理员能管成员");
        }
    }

    /**
     * 落位成员关系：已存在就改角色，不报冲突。
     * 邀请可以被重复接受，所以这里必须是 upsert 而不是 insert。
     *
     * 只升不降——当前角色已经 ≥ 给的角色就保持不变。
     * 否则唯一 owner 接受一份 member 邀请会被降级，组织从此没有 owner，
     * 而 owner 只能由现任 owner 任命——那是永久锁死。
     *
     * 前置条件：调用方要自己保证 actor 有资格改这个人的角色。
     * 这里只挡了 owner 这一档（requireOwnerToGrantOwner），别的角色档位由调用方把关。
     */
    @Transactional
    public void upsertOrgMember(CurrentUser actor, UUID organizationId, UUID memberId, String role) {
        requireRole(role, Roles.ORG_ROLES);
        requireOwnerToGrantOwner(actor, organizationId, role);
        MembershipEntities.OrgKey key = new MembershipEntities.OrgKey(organizationId, memberId);
        MembershipEntities.OrgMembership membership = orgMembers.findById(key).orElseGet(() -> {
            MembershipEntities.OrgMembership created = new MembershipEntities.OrgMembership();
            created.setId(key);
            created.setJoinedAt(OffsetDateTime.now(ZoneOffset.UTC));
            return created;
        });
        if (!roleAtLeast(membership.getRole(), role)) {
            membership.setRole(role);
        }
        orgMembers.save(membership);
        audit.record(actor, "organization.membership.grant", "organization", organizationId,
            "success", Map.of("user_id", memberId.toString(), "role", role));
    }

    /** 同 upsertOrgMember：只升不降，调用方自己保证 actor 有资格。 */
    @Transactional
    public void upsertTeamMember(CurrentUser actor, UUID teamId, UUID memberId, String role) {
        requireRole(role, Roles.TEAM_ROLES);
        MembershipEntities.TeamKey key = new MembershipEntities.TeamKey(teamId, memberId);
        MembershipEntities.TeamMembership membership = teamMembers.findById(key).orElseGet(() -> {
            MembershipEntities.TeamMembership created = new MembershipEntities.TeamMembership();
            created.setId(key);
            created.setJoinedAt(OffsetDateTime.now(ZoneOffset.UTC));
            return created;
        });
        if (!roleAtLeast(membership.getRole(), role)) {
            membership.setRole(role);
        }
        teamMembers.save(membership);
        audit.record(actor, "team.membership.grant", "team", teamId,
            "success", Map.of("user_id", memberId.toString(), "role", role));
    }

    /** 组织成员列表，带上人的显示名。 */
    public List<Member> orgMembers(UUID organizationId) {
        return orgMembers.findByIdOrganizationId(organizationId).stream()
            .map(membership -> toMember(membership.getId().getUserId(),
                membership.getRole(), membership.getJoinedAt()))
            .toList();
    }

    /** 团队成员列表。 */
    public List<Member> teamMembers(UUID teamId) {
        return teamMembers.findByIdTeamId(teamId).stream()
            .map(membership -> toMember(membership.getId().getUserId(),
                membership.getRole(), membership.getJoinedAt()))
            .toList();
    }

    /**
     * 改组织角色。
     *
     * 不走 upsertOrgMember：那个只升不降，这里就是要能降。
     * 所以 owner 守卫得自己调一次，指望不上上游。
     */
    @Transactional
    public void updateOrgRole(CurrentUser actor, UUID organizationId, UUID memberId, String role) {
        requireOrgAdmin(actor, organizationId);
        requireRole(role, Roles.ORG_ROLES);
        // 授予 owner 同样要现任 owner 在场——这个方法不走 upsertOrgMember，
        // 所以必须自己调一次守卫，不能指望上游覆盖。
        requireOwnerToGrantOwner(actor, organizationId, role);
        if (actor.id().equals(memberId)) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "不能改自己的组织角色");
        }
        MembershipEntities.OrgKey key = new MembershipEntities.OrgKey(organizationId, memberId);
        MembershipEntities.OrgMembership membership = orgMembers.findById(key)
            .orElseThrow(() -> ApiException.notFound("成员"));
        requireNotLastOwner(organizationId, memberId, role);
        membership.setRole(role);
        orgMembers.save(membership);
        audit.record(actor, "organization.membership.update", "organization", organizationId,
            "success", Map.of("user_id", memberId.toString(), "role", role));
    }

    /** 改团队角色。 */
    @Transactional
    public void updateTeamRole(CurrentUser actor, UUID teamId, UUID memberId, String role) {
        requireTeamAdmin(actor, teamId);
        requireRole(role, Roles.TEAM_ROLES);
        MembershipEntities.TeamKey key = new MembershipEntities.TeamKey(teamId, memberId);
        MembershipEntities.TeamMembership membership = teamMembers.findById(key)
            .orElseThrow(() -> ApiException.notFound("成员"));
        membership.setRole(role);
        teamMembers.save(membership);
        audit.record(actor, "team.membership.update", "team", teamId,
            "success", Map.of("user_id", memberId.toString(), "role", role));
    }

    /** 移出团队。不动组织成员身份——两套身份是独立的。 */
    @Transactional
    public void removeTeamMember(CurrentUser actor, UUID teamId, UUID memberId) {
        requireTeamAdmin(actor, teamId);
        MembershipEntities.TeamKey key = new MembershipEntities.TeamKey(teamId, memberId);
        if (teamMembers.findById(key).isEmpty()) {
            throw ApiException.notFound("成员");
        }
        teamMembers.deleteById(key);
        audit.record(actor, "team.membership.remove", "team", teamId,
            "success", Map.of("user_id", memberId.toString()));
    }

    /** 这个团队是不是这个组织的？换个组织 id 就能看别人团队成员，那不行。 */
    public boolean teamInOrg(UUID organizationId, UUID teamId) {
        return teams.findById(teamId)
            .map(team -> team.getOrganizationId().equals(organizationId))
            .orElse(false);
    }

    // ponytail: 逐个查 users（N+1），成员表规模不大；量大时改成一次批量查。
    private Member toMember(UUID userId, String role, OffsetDateTime joinedAt) {
        String displayName = userId.toString();
        String email = null;
        UserEntity user = users.findById(userId).orElse(null);
        if (user != null) {
            displayName = user.getDisplayName();
            email = user.getEmail();
        }
        return new Member(userId, displayName, email, role, joinedAt);
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

    /**
     * 授予 owner 只能由现任 owner 亲手做。
     * admin 能任命 owner，就等于能把自己人提上去再踢出组织——那是提权。
     *
     * 包内可见：同包的改角色入口（updateOrgRole）要自己调这道守卫。
     */
    void requireOwnerToGrantOwner(CurrentUser actor, UUID organizationId, String role) {
        if ("owner".equals(role) && !hasOrgRole(actor, organizationId, Set.of("owner"))) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "只有组织 owner 能授予 owner 角色");
        }
    }

    /**
     * 不能把组织里最后一个 owner 降级。
     * 降了之后 requireOwnerToGrantOwner 再也满足不了——组织永久失去 owner。
     *
     * 两个 owner 时降掉一个是允许的：还剩一个 owner 能任命人。
     */
    private void requireNotLastOwner(UUID organizationId, UUID memberId, String role) {
        if ("owner".equals(role)) {
            return;
        }
        boolean isOwner = orgMembers.findById(new MembershipEntities.OrgKey(organizationId, memberId))
            .map(membership -> "owner".equals(membership.getRole()))
            .orElse(false);
        if (!isOwner) {
            return;
        }
        long ownerCount = orgMembers.findByIdOrganizationId(organizationId).stream()
            .filter(membership -> "owner".equals(membership.getRole()))
            .count();
        if (ownerCount <= 1) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "不能降级组织里唯一的 owner");
        }
    }

    /** 我在某个组织里的身份。 */
    public record Membership(UUID id, String slug, String name, String role) {}

    /** 成员列表项。 */
    public record Member(UUID userId, String displayName, String email, String role,
                         OffsetDateTime joinedAt) {}
}
