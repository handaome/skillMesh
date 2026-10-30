package com.skillmesh.api.org.dto;

import java.util.List;

/**
 * 我在这个组织下的权限清单。
 *
 * 给前端的：按钮显隐按它来——没有的权限点了也是 403，不如别显示。
 * 服务端仍然是最终裁决者，这份清单只决定界面长什么样。
 *
 * 三种作用域各给一份，语义与 AuthorizationService 一致：
 * 组织域、组织角色与团队角色的并集（管团队成员用）、
 * 团队优先回落（审核权用）。
 */
public record OrgPermissionsResponse(
    String organization_id,
    String role,
    List<String> organization_permissions,
    List<TeamPermissions> teams) {

    /**
     * 一个团队上的权限。
     *
     * scope_permissions 是团队优先回落后的结果（审核权看这个）；
     * member_permissions 是团队与组织的并集（管团队成员看这个）。
     * 两者会不一样：既是某团队的普通成员、又是组织管理员的人，
     * 审核权按团队角色算（不能审），管成员按并集算（能管）。
     */
    public record TeamPermissions(
        String team_id,
        List<String> scope_permissions,
        List<String> member_permissions) {}
}
