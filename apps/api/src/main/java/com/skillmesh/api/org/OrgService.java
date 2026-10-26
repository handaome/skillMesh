package com.skillmesh.api.org;

import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.UserEntity;
import com.skillmesh.api.auth.UserRepository;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.org.dto.MemberResponse;
import com.skillmesh.api.org.dto.OrgMembershipResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
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

    /** 接口能设的账号状态。库里还允许 pending，那一档没有对应的操作。 */
    private static final List<String> ALLOWED_STATUSES = List.of("active", "disabled");

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
        owner.setRole(Roles.OWNER_ROLE);
        owner.setJoinedAt(now);
        orgMembers.save(owner);

        audit.record(user, "organization.create", "organization", org.getId(), "success",
            Map.of("slug", slug));
        return org;
    }

    @Transactional
    public TeamEntity createTeam(CurrentUser user, UUID organizationId, String slug, String name,
                                String description) {
        requireOrgRole(user, organizationId, Roles.ORG_ADMIN_ROLES);
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

    /**
     * 加团队成员。
     *
     * 与 upsertOrgMember 同一口径：已是成员时保留 joined_at，角色只升不降——
     * 「加成员」不该顺手削掉别人的权限（那会让人立刻失去审核放行权），
     * 要降级走 PATCH /teams/{teamId}/members/{userId} 那个显式端点。
     */
    @Transactional
    public void addTeamMember(CurrentUser actor, UUID teamId, UUID memberId, String role) {
        TeamEntity team = teams.findById(teamId)
            .orElseThrow(() -> ApiException.notFound("团队"));
        // 组织 owner/admin 或团队 admin 都能加成员
        boolean orgAdmin = hasOrgRole(actor, team.getOrganizationId(), Roles.ORG_ADMIN_ROLES);
        boolean teamAdmin = teamMembers.findById(
                new MembershipEntities.TeamKey(teamId, actor.id()))
            .map(MembershipEntities.TeamMembership::getRole)
            .filter(Roles.TEAM_ADMIN_ROLES::contains)
            .isPresent();
        if (!orgAdmin && !teamAdmin) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "只有组织或团队管理员能加成员");
        }
        requireRole(role, Roles.TEAM_ROLES);
        MembershipEntities.TeamKey key = new MembershipEntities.TeamKey(teamId, memberId);
        MembershipEntities.TeamMembership membership = teamMembers.findById(key).orElse(null);
        if (membership == null) {
            membership = new MembershipEntities.TeamMembership();
            membership.setId(key);
            membership.setJoinedAt(OffsetDateTime.now(ZoneOffset.UTC));
            membership.setRole(role);
        } else if (!roleAtLeast(membership.getRole(), role)) {
            membership.setRole(role);
        }
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
    public List<OrgMembershipResponse> myOrganizations(UUID userId) {
        List<MembershipEntities.OrgMembership> memberships = orgMembers.findByIdUserId(userId);
        Map<UUID, OrganizationEntity> orgById = orgsById(memberships.stream()
            .map(membership -> membership.getId().getOrganizationId()).toList());
        List<OrgMembershipResponse> result = new ArrayList<>();
        for (MembershipEntities.OrgMembership membership : memberships) {
            OrganizationEntity org = orgById.get(membership.getId().getOrganizationId());
            if (org == null) {
                continue;
            }
            result.add(new OrgMembershipResponse(org.getId(), org.getSlug(), org.getName(),
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

    /** 这个人在这个组织的实际角色；没有成员行就是空。 */
    public String orgRoleOf(UUID organizationId, UUID userId) {
        return orgMembers.findById(new MembershipEntities.OrgKey(organizationId, userId))
            .map(MembershipEntities.OrgMembership::getRole)
            .orElse(null);
    }

    /** 这个人在这个团队的实际角色；没有成员行就是空。 */
    public String teamRoleOf(UUID teamId, UUID userId) {
        return teamMembers.findById(new MembershipEntities.TeamKey(teamId, userId))
            .map(MembershipEntities.TeamMembership::getRole)
            .orElse(null);
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

    public List<TeamEntity> teamsOf(UUID organizationId) {
        return teams.findByOrganizationIdOrderByCreatedAtAsc(organizationId);
    }

    /** 组织管理员以上才能管成员与邀请。 */
    public void requireOrgAdmin(CurrentUser user, UUID organizationId) {
        requireOrgRole(user, organizationId, Roles.ORG_ADMIN_ROLES);
    }

    /** 团队管理员：组织 owner/admin 或团队 admin 都能管团队成员。 */
    public void requireTeamAdmin(CurrentUser user, UUID teamId) {
        TeamEntity team = teams.findById(teamId).orElseThrow(() -> ApiException.notFound("团队"));
        if (hasOrgRole(user, team.getOrganizationId(), Roles.ORG_ADMIN_ROLES)) {
            return;
        }
        boolean teamAdmin = teamMembers.findById(new MembershipEntities.TeamKey(teamId, user.id()))
            .map(MembershipEntities.TeamMembership::getRole)
            .filter(Roles.TEAM_ADMIN_ROLES::contains)
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
    public List<MemberResponse> orgMembers(UUID organizationId) {
        List<MembershipEntities.OrgMembership> memberships =
            orgMembers.findByIdOrganizationId(organizationId);
        Map<UUID, UserEntity> userById = usersById(memberships.stream()
            .map(membership -> membership.getId().getUserId()).toList());
        return memberships.stream()
            .map(membership -> toMember(userById.get(membership.getId().getUserId()),
                membership.getId().getUserId(), membership.getRole(), membership.getJoinedAt()))
            .toList();
    }

    /** 团队成员列表。 */
    public List<MemberResponse> teamMembers(UUID teamId) {
        List<MembershipEntities.TeamMembership> memberships =
            teamMembers.findByIdTeamId(teamId);
        Map<UUID, UserEntity> userById = usersById(memberships.stream()
            .map(membership -> membership.getId().getUserId()).toList());
        return memberships.stream()
            .map(membership -> toMember(userById.get(membership.getId().getUserId()),
                membership.getId().getUserId(), membership.getRole(), membership.getJoinedAt()))
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
        // 锁必须先拿：owner 计数是「读-判-写」，不串行化就会竞态。
        lockOrgForRoleChange(organizationId);
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
        requireOwnerChangeAllowed(actor, organizationId, membership.getRole(), role);
        membership.setRole(role);
        orgMembers.save(membership);
        audit.record(actor, "organization.membership.update", "organization", organizationId,
            "success", Map.of("user_id", memberId.toString(), "role", role));
    }

    /**
     * 改组织成员：角色与账号状态都能改，都能不给，但至少要改一项。
     *
     * 状态先改、角色后改：「owner 不能被禁用」按请求提交时的角色判定——
     * 降级与禁用得分两次请求，先降级再禁用。
     */
    @Transactional
    public void updateOrgMember(CurrentUser actor, UUID organizationId, UUID memberId,
                                 String role, String status) {
        if (role == null && status == null) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "角色与账号状态至少要改一项");
        }
        if (status != null) {
            updateMemberStatus(actor, organizationId, memberId, status);
        }
        if (role != null) {
            updateOrgRole(actor, organizationId, memberId, role);
        }
    }

    /**
     * 禁用/启用组织成员。
     *
     * 口径跟改角色一致：调用者得是该组织的 owner 或 admin。
     * 另外两条更严——不能禁用自己的账号；不能禁用组织 owner
     * （owner 是组织的最后一道保险，禁掉他就没人能任命下一个了）。
     */
    private void updateMemberStatus(CurrentUser actor, UUID organizationId, UUID memberId,
                                    String status) {
        // 锁先拿：owner 的判定要跟并发的角色变更串起来。
        lockOrgForRoleChange(organizationId);
        requireOrgAdmin(actor, organizationId);
        if (!ALLOWED_STATUSES.contains(status)) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "账号状态只能是 " + String.join(" / ", ALLOWED_STATUSES));
        }
        MembershipEntities.OrgKey key = new MembershipEntities.OrgKey(organizationId, memberId);
        MembershipEntities.OrgMembership membership = orgMembers.findById(key)
            .orElseThrow(() -> ApiException.notFound("成员"));
        if ("disabled".equals(status)) {
            if (actor.id().equals(memberId)) {
                throw new ApiException(403, ErrorCodes.FORBIDDEN, "不能禁用自己的账号");
            }
            if (hasRole(Roles.ORG_OWNER_ROLES, membership.getRole())) {
                throw new ApiException(403, ErrorCodes.FORBIDDEN, "组织 owner 不能被禁用");
            }
        }
        UserEntity user = users.findById(memberId)
            .orElseThrow(() -> ApiException.notFound("用户"));
        user.setStatus(status);
        user.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        users.save(user);
        audit.record(actor, "organization.membership.status", "organization", organizationId,
            "success", Map.of("user_id", memberId.toString(), "status", status));
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

    /**
     * 团队必须属于这个组织。读路径和写路径都要过这一道，
     * 否则同一个资源路径会给出不一致的答案。
     */
    public void requireTeamInOrg(UUID organizationId, UUID teamId) {
        if (!teamInOrg(organizationId, teamId)) {
            throw ApiException.notFound("团队");
        }
    }

    // ponytail: users 一次查齐（见 usersById）——逐个查就是 N+1，
    // 一个组织几十个成员就是几十次往返，本机到远程库一次几十毫秒，这个页面直接卡住。
    private MemberResponse toMember(UserEntity user, UUID userId, String role,
                                     OffsetDateTime joinedAt) {
        String displayName = userId.toString();
        String email = null;
        String subject = null;
        String status = null;
        if (user != null) {
            displayName = user.getDisplayName();
            email = user.getEmail();
            subject = user.getOidcSubject();
            status = user.getStatus();
        }
        return new MemberResponse(userId, displayName, email, role, joinedAt, subject, status);
    }

    /**
     * 批量取的行按 id 归拢成 Map。
     *
     * 查不到的 id 不在这里报错——调用方按自己的口径处理（成员表对 users 有外键，
     * 理论上查得到，但兜底比抛异常稳）。
     */
    private Map<UUID, UserEntity> usersById(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UserEntity> out = new LinkedHashMap<>();
        for (UserEntity user : users.findAllById(ids)) {
            out.put(user.getId(), user);
        }
        return out;
    }

    /** 批量取组织，按 id 归拢——形状同 {@link #usersById}。 */
    private Map<UUID, OrganizationEntity> orgsById(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, OrganizationEntity> out = new LinkedHashMap<>();
        for (OrganizationEntity org : organizations.findAllById(ids)) {
            out.put(org.getId(), org);
        }
        return out;
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
        if (hasRole(Roles.ORG_OWNER_ROLES, role)
            && !hasOrgRole(actor, organizationId, Roles.ORG_OWNER_ROLES)) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "只有组织 owner 能授予 owner 角色");
        }
    }

    /**
     * 改变 owner 身份（授予或剥夺）只能由 owner 动手；
     * 且组织里最后一个 owner 不能被降级——降了之后
     * requireOwnerToGrantOwner 再也满足不了，组织永久失去 owner。
     *
     * currentRole 由调用方传入：调用方已经把这行读出来了，
     * 这里再查一遍只会拿到可能过期的值。
     */
    private void requireOwnerChangeAllowed(CurrentUser actor, UUID organizationId,
                                           String currentRole, String newRole) {
        boolean touchesOwnership = hasRole(Roles.ORG_OWNER_ROLES, currentRole)
            || hasRole(Roles.ORG_OWNER_ROLES, newRole);
        if (!touchesOwnership) {
            return;
        }
        if (!hasOrgRole(actor, organizationId, Roles.ORG_OWNER_ROLES)) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "只有组织 owner 能改变 owner 身份");
        }
        if (hasRole(Roles.ORG_OWNER_ROLES, currentRole) && !hasRole(Roles.ORG_OWNER_ROLES, newRole)
            && ownerCount(organizationId) <= 1) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "不能降级组织里唯一的 owner");
        }
    }

    /** 组织里 owner 角色的成员数。 */
    private long ownerCount(UUID organizationId) {
        return orgMembers.findByIdOrganizationId(organizationId).stream()
            .filter(membership -> hasRole(Roles.ORG_OWNER_ROLES, membership.getRole()))
            .count();
    }

    /** 档位判断——Set.of 的 contains(null) 会抛 NPE，而角色可能为空。 */
    private static boolean hasRole(Set<String> roles, String role) {
        return role != null && roles.contains(role);
    }

    /**
     * 锁住组织行（SELECT ... FOR UPDATE）：组织内的角色变更串行化。
     *
     * owner 计数是「读-判-写」，不串行化的话两个并发降级都能看到 ownerCount == 2，
     * 各自降一个，组织就剩 0 个 owner——永久锁死。
     *
     * 必须是事务里的第一条语句：成员行一旦进了持久化上下文，
     * 锁之后的读还是旧值，判断就建立在陈旧数据上了。
     */
    private void lockOrgForRoleChange(UUID organizationId) {
        // 组织不存在就当没锁住——后面的权限校验会给出 403。
        organizations.findByIdForUpdate(organizationId);
    }
}
