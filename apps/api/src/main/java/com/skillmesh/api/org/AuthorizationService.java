package com.skillmesh.api.org;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 授权入口：判定「这个人能不能做这件事」。
 *
 * 全站的权限判断都走这里，别在各处手写角色名门槛——门槛字符串散开后，
 * 加一档角色要改十几个地方，漏一处就是一条越权路径。
 *
 * 三种作用域，取角色的方式不同（这是现有行为，不是随意的设计）：
 * <ul>
 *   <li><b>组织域</b>（{@link #requireOrgPermission}）：只看组织角色。
 *       发布权走这条——接受邀请会写一行 team_role=member，
 *       团队角色若优先取用会把组织角色永久遮住，手册教的「把组织角色提到 publisher」就不生效了。</li>
 *   <li><b>团队优先</b>（{@link #requirePermission} 传了 teamId）：有团队成员行就用团队角色，
 *       没有才回落组织角色。审核权走这条——审核是团队范围内的事。</li>
 *   <li><b>跨域</b>（{@link #requireTeamPermission}）：团队角色与组织角色的权限并集。
 *       管团队成员走这条——组织 owner/admin 能管任意团队，团队 admin 只能管自己团队。</li>
 * </ul>
 *
 * 归属（属主才能改的东西）与角色是两回事，不在这套体系里：
 * 那几处用 {@link #requireOwner} 单独判。
 */
@Service
public class AuthorizationService {

    private final OrgMembershipRepository orgMembers;
    private final TeamMembershipRepository teamMembers;
    private final TeamRepository teams;

    public AuthorizationService(OrgMembershipRepository orgMembers,
                                TeamMembershipRepository teamMembers,
                                TeamRepository teams) {
        this.orgMembers = orgMembers;
        this.teamMembers = teamMembers;
        this.teams = teams;
    }

    // ——— 角色解析 ———

    /** 这个人在这个组织的组织角色；不是成员就是空串。 */
    public String orgRole(UUID userId, UUID organizationId) {
        if (organizationId == null) {
            return "";
        }
        return orgMembers.findById(new MembershipEntities.OrgKey(organizationId, userId))
            .map(MembershipEntities.OrgMembership::getRole)
            .orElse("");
    }

    /** 这个人在这个团队的角色；不是成员就是空串。 */
    public String teamRole(UUID userId, UUID teamId) {
        if (teamId == null) {
            return "";
        }
        return teamMembers.findById(new MembershipEntities.TeamKey(teamId, userId))
            .map(MembershipEntities.TeamMembership::getRole)
            .orElse("");
    }

    /**
     * 这个人在某个范围内的有效角色：团队成员行优先，没有团队成员行才回落组织角色。
     *
     * 同一个人可能两个身份都有，取更近的那个。互斥而非并集——
     * 团队角色优先是有意的，见类注释。
     */
    public String effectiveRole(UUID userId, UUID organizationId, UUID teamId) {
        if (teamId != null) {
            String teamRole = teamRole(userId, teamId);
            if (!teamRole.isEmpty()) {
                return teamRole;
            }
        }
        return orgRole(userId, organizationId);
    }

    // ——— 权限集合 ———
    //
    // 判定用权限点，界面也要用：前端要按权限决定按钮显隐，
    // 没有的权限点了也是 403，不如不显示。三种作用域各给一份集合。

    /** 组织域的权限点。只看组织角色。 */
    public Set<Permission> orgPermissionSet(UUID userId, UUID organizationId) {
        return RolePermissions.orgPermissions(orgRole(userId, organizationId));
    }

    /** 范围权限：团队成员行优先，没有团队成员行才回落组织角色。审核权看这个。 */
    public Set<Permission> scopePermissionSet(UUID userId, UUID organizationId, UUID teamId) {
        if (teamId != null) {
            String role = teamRole(userId, teamId);
            if (!role.isEmpty()) {
                return RolePermissions.teamPermissions(role);
            }
        }
        return orgPermissionSet(userId, organizationId);
    }

    /** 团队域权限：团队角色与组织角色的并集。管团队成员看这个。 */
    public Set<Permission> unionPermissionSet(UUID userId, UUID organizationId, UUID teamId) {
        Set<Permission> result =
            new LinkedHashSet<>(RolePermissions.teamPermissions(teamRole(userId, teamId)));
        result.addAll(orgPermissionSet(userId, organizationId));
        return result;
    }

    /** 权限点集合转名字列表。排了序，前端拿到的是稳定顺序。 */
    public static List<String> names(Set<Permission> permissions) {
        return permissions.stream().map(Permission::name).sorted().toList();
    }

    // ——— 权限判定 ———

    /**
     * 组织域权限：只看组织角色。
     *
     * 团队角色在这里一律不算数——它会遮住组织角色，而组织角色才是这一域的权限来源。
     */
    public boolean hasOrgPermission(CurrentUser user, Permission permission,
                                    UUID organizationId) {
        if (organizationId == null) {
            return false;
        }
        return orgPermissionSet(user.id(), organizationId).contains(permission);
    }

    /**
     * 范围权限：按有效角色（团队优先）判定。
     *
     * teamId 为 null 时只看组织角色；给了 teamId 且用户是该团队成员时只看团队角色。
     * organizationId 为 null 判否——个人空间没有组织角色，不该由这里放行。
     */
    public boolean hasPermission(CurrentUser user, Permission permission,
                                 UUID organizationId, UUID teamId) {
        return scopePermissionSet(user.id(), organizationId, teamId).contains(permission);
    }

    /**
     * 团队域权限：团队角色与组织角色的权限并集。
     *
     * 团队 admin 能管自己团队；组织 owner/admin 能管任意团队——
     * 他们建团队时没有团队成员行，只按团队角色判会判成「不是管理员」。
     */
    public boolean hasTeamPermission(CurrentUser user, Permission permission, UUID teamId) {
        TeamEntity team = teams.findById(teamId).orElse(null);
        if (team == null) {
            return false;
        }
        return unionPermissionSet(user.id(), team.getOrganizationId(), teamId).contains(permission);
    }

    // ——— 断言 ———

    /** 组织域权限不足就拒。 */
    public void requireOrgPermission(CurrentUser user, Permission permission,
                                     UUID organizationId) {
        if (!hasOrgPermission(user, permission, organizationId)) {
            throw denied(permission, orgRole(user.id(), organizationId));
        }
    }

    /** 范围权限不足就拒。 */
    public void requirePermission(CurrentUser user, Permission permission,
                                  UUID organizationId, UUID teamId) {
        if (!hasPermission(user, permission, organizationId, teamId)) {
            throw denied(permission, effectiveRole(user.id(), organizationId, teamId));
        }
    }

    /** 团队域权限不足就拒。团队不存在按 404 报——与其它资源同一口径。 */
    public void requireTeamPermission(CurrentUser user, Permission permission, UUID teamId) {
        TeamEntity team = teams.findById(teamId).orElseThrow(() -> ApiException.notFound("团队"));
        String role = teamRole(user.id(), teamId);
        if (!hasTeamPermission(user, permission, teamId)) {
            throw denied(permission, role.isEmpty() ? orgRole(user.id(), team.getOrganizationId()) : role);
        }
    }

    // ——— 归属 ———

    /** 我是不是这个资源的属主。 */
    public boolean isOwner(CurrentUser user, UUID ownerUserId) {
        return ownerUserId != null && ownerUserId.equals(user.id());
    }

    /** 不是属主就拒。归属不是角色：组织 admin 也改不了别人的 Skill。 */
    public void requireOwner(CurrentUser user, UUID ownerUserId, String message) {
        if (!isOwner(user, ownerUserId)) {
            throw ApiException.forbidden(message);
        }
    }

    /** 拒绝时的提示：说清缺哪个权限、当前是什么角色。 */
    private ApiException denied(Permission permission, String role) {
        return ApiException.forbidden("缺少权限：" + permission.label()
            + "（当前角色：" + (role == null || role.isEmpty() ? "无" : role) + "）");
    }
}
