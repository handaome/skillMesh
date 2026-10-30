package com.skillmesh.api.org;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 角色 → 权限的映射。全站只有这一份。
 *
 * 两张表：组织角色与团队角色。同名的 admin 在两个域里权限不同——
 * 组织 admin 能建团队、能管所有团队的成员，团队 admin 只能管自己团队。
 *
 * 映射是代码里的常量，不是数据库表：角色词表本身也是代码里的常量，
 * 两边放一处才不会漂。要支持运行期改权限，再把这里换成查表即可——
 * 判定点全在 {@link AuthorizationService}，换数据源不用改别处。
 *
 * 权限集合的边界就是现有行为，改这张表等于改行为，得连带改测试。
 */
public final class RolePermissions {

    /** 组织角色 → 权限。 */
    private static final Map<String, Set<Permission>> ORG = Map.of(
        // owner 拥有一切：授予/转移 owner 是他独有的权限，其余一律向下继承。
        Roles.OWNER_ROLE, EnumSet.allOf(Permission.class),
        // admin 能管成员、邀请、建团队、管任意团队的成员；
        // 但给不了 owner 也不动 owner——那两档是 owner 独有的。
        Roles.ADMIN_ROLE, EnumSet.of(
            Permission.ORG_VIEW_MEMBERS,
            Permission.ORG_INVITE,
            Permission.ORG_MANAGE_MEMBERS,
            Permission.ORG_MANAGE_TEAMS,
            Permission.TEAM_VIEW_MEMBERS,
            Permission.TEAM_MANAGE_MEMBERS,
            Permission.SKILL_PUBLISH,
            Permission.SKILL_REVIEW,
            Permission.GROUP_PUBLISH),
        // publisher 能发布，reviewer 能审核——这两个是分开的两件事。
        Roles.PUBLISHER_ROLE, EnumSet.of(
            Permission.ORG_VIEW_MEMBERS,
            Permission.TEAM_VIEW_MEMBERS,
            Permission.SKILL_PUBLISH,
            Permission.SKILL_REVIEW,
            Permission.GROUP_PUBLISH),
        "reviewer", EnumSet.of(
            Permission.ORG_VIEW_MEMBERS,
            Permission.TEAM_VIEW_MEMBERS,
            Permission.SKILL_REVIEW),
        Roles.DEFAULT_ROLE, EnumSet.of(
            Permission.ORG_VIEW_MEMBERS,
            Permission.TEAM_VIEW_MEMBERS),
        "viewer", EnumSet.of(
            Permission.ORG_VIEW_MEMBERS,
            Permission.TEAM_VIEW_MEMBERS));

    /** 团队角色 → 权限。团队里没有 owner 这一档。 */
    private static final Map<String, Set<Permission>> TEAM = Map.of(
        "admin", EnumSet.of(
            Permission.TEAM_VIEW_MEMBERS,
            Permission.TEAM_MANAGE_MEMBERS,
            Permission.SKILL_REVIEW,
            Permission.GROUP_PUBLISH),
        "publisher", EnumSet.of(
            Permission.TEAM_VIEW_MEMBERS,
            Permission.SKILL_REVIEW,
            Permission.GROUP_PUBLISH),
        "reviewer", EnumSet.of(
            Permission.TEAM_VIEW_MEMBERS,
            Permission.SKILL_REVIEW),
        Roles.DEFAULT_ROLE, EnumSet.of(Permission.TEAM_VIEW_MEMBERS),
        "viewer", EnumSet.of(Permission.TEAM_VIEW_MEMBERS));

    /**
     * 某档组织角色拥有的权限。角色不认识（不是词表里的）就是没有权限。
     *
     * 传 null 是安全的：词表里查不到就返回空集，不会 NPE。
     */
    public static Set<Permission> orgPermissions(String role) {
        return role == null ? Set.of() : ORG.getOrDefault(role, Set.of());
    }

    /** 某档团队角色拥有的权限。 */
    public static Set<Permission> teamPermissions(String role) {
        return role == null ? Set.of() : TEAM.getOrDefault(role, Set.of());
    }

    /**
     * 角色是否达到要求（owner &gt; admin &gt; publisher &gt; reviewer &gt; member &gt; viewer）。
     *
     * 这是「等级比较」不是「权限判定」——只有升不降这类场景才用它
     * （成员行已经比目标档位高时保持不变，免得把 owner 降成 member）。
     * 能不能做某件事，认 {@link Permission}，别用这个。
     */
    public static boolean atLeast(String actual, String required) {
        if (actual == null || required == null) {
            return false;
        }
        int have = Roles.ORG_RANK.indexOf(actual);
        int need = Roles.ORG_RANK.indexOf(required);
        return have >= 0 && need >= 0 && have <= need;
    }

    static {
        // 词表与映射必须一一对上：词表加了新角色而映射没跟上，那档角色就静默地什么都不能做。
        if (!ORG.keySet().equals(new HashSet<>(Roles.ORG_ROLES))) {
            throw new IllegalStateException("组织角色映射与角色词表对不上");
        }
        if (!TEAM.keySet().equals(new HashSet<>(Roles.TEAM_ROLES))) {
            throw new IllegalStateException("团队角色映射与角色词表对不上");
        }
        // 空权限集会让「判定永远不通过」变成「判定永远通过」——两处都坏，取其一更安全。
        for (Set<Permission> permissions : ORG.values()) {
            if (permissions.isEmpty()) {
                throw new IllegalStateException("组织角色映射里出现了空权限集");
            }
        }
        for (Set<Permission> permissions : TEAM.values()) {
            if (permissions.isEmpty()) {
                throw new IllegalStateException("团队角色映射里出现了空权限集");
            }
        }
    }

    private RolePermissions() {}
}
