package com.skillmesh.api.org;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 角色词表的派生视图。
 *
 * 真相在 {@link Role} 枚举里：等级顺序、可用范围、正则与 SQL 片段
 * 全从它算出来——词表只有一份，往上加一档角色只改枚举，
 * 这里跟着自动跟上，不会出现「映射里没有、词表里有」的静默失效。
 *
 * 组织 owner &gt; admin &gt; publisher &gt; reviewer &gt; member &gt; viewer，
 * 团队没有 owner 那一档。
 */
public final class Roles {

    /** 组织角色的等级顺序，从高到低。 */
    public static final List<Role> ORG_RANK = List.of(Role.values());

    /** 团队角色的等级顺序，从高到低——团队里没有 owner 这一档。 */
    public static final List<Role> TEAM_RANK = ORG_RANK.stream()
        .filter(role -> !role.orgOnly())
        .toList();

    /** 成员行能放的组织角色全集（含 owner）。 */
    public static final Set<Role> ORG_ROLES = Set.copyOf(ORG_RANK);

    /** 成员行能放的团队角色全集。 */
    public static final Set<Role> TEAM_ROLES = Set.copyOf(TEAM_RANK);

    /**
     * 组织管理员档位（owner 与 admin）：能管成员、邀请、团队。
     *
     * 从词表推出来，不手敲字面量——往词表里加一档新角色时权限判断自动认它。
     *
     * 只用于词表自检与「这一档是不是管理员」的查询；
     * 能不能做某件事认 {@link RolePermissions} 里的权限点，改这里不会改权限。
     */
    public static final Set<Role> ORG_ADMIN_ROLES = atOrAbove(ORG_RANK, Role.admin);

    /** 组织 owner 档位：改 owner 身份只有这一档能做。 */
    public static final Set<Role> ORG_OWNER_ROLES = atOrAbove(ORG_RANK, Role.owner);

    /** 团队管理员档位。同样只用于词表自检，权限判定见 {@link RolePermissions}。 */
    public static final Set<Role> TEAM_ADMIN_ROLES = atOrAbove(TEAM_RANK, Role.admin);

    /** 邀请能授的组织角色（有序）：ORG_RANK 去掉 owner。 */
    public static final List<Role> INVITABLE_ORG_RANK = ORG_RANK.stream()
        .filter(role -> !role.orgOnly())
        .toList();

    /** 邀请能授的组织角色全集。 */
    public static final Set<Role> INVITABLE_ORG_ROLES = Set.copyOf(INVITABLE_ORG_RANK);

    /**
     * @Pattern 用的正则——编译期常量，不能在运行时拼。
     * 邀请的组织角色不含 owner：owner 只能由现任 owner 直接任命。
     */
    public static final String INVITABLE_ORG_ROLE_PATTERN = pattern(INVITABLE_ORG_RANK);

    /** @Pattern 用的正则——编译期常量。 */
    public static final String TEAM_ROLE_PATTERN = pattern(TEAM_RANK);

    /**
     * 组织管理员档位在原生 SQL 里的写法：{@code 'owner', 'admin'}。
     *
     * 原生 SQL 走 {@code @Query}，片段必须是编译期常量——枚举的 name()
     * 拼不出常量表达式（javac 报「注解值不必须是常量表达式」），
     * 所以这一份只能手写，靠下面的静态块盯着它与词表不漂。
     */
    public static final String ORG_ADMIN_ROLE_SQL = "'owner', 'admin'";

    /** 词表里某档及以上的所有档位（含该档）。 */
    private static Set<Role> atOrAbove(List<Role> rank, Role role) {
        int index = rank.indexOf(role);
        if (index < 0) {
            throw new IllegalStateException("词表里没有 " + role + " 这一档");
        }
        return Set.copyOf(rank.subList(0, index + 1));
    }

    /** 角色列表拼成正则：{@code admin|publisher|reviewer|member|viewer}。 */
    private static String pattern(List<Role> roles) {
        return roles.stream().map(Role::name).collect(Collectors.joining("|"));
    }

    static {
        // 档位集合必须是词表的子集，而且不能是空集——空集合会让权限判断悄悄失效。
        if (ORG_ADMIN_ROLES.isEmpty() || !ORG_ROLES.containsAll(ORG_ADMIN_ROLES)
            || ORG_OWNER_ROLES.isEmpty() || !ORG_ROLES.containsAll(ORG_OWNER_ROLES)
            || TEAM_ADMIN_ROLES.isEmpty() || !TEAM_ROLES.containsAll(TEAM_ADMIN_ROLES)
            || !ORG_ROLES.contains(Role.member) || !TEAM_ROLES.contains(Role.member)) {
            throw new IllegalStateException("角色档位集合与词表对不上");
        }
        // 团队里不该有 owner：owner 只能由现任 owner 直接任命。
        if (TEAM_ROLES.contains(Role.owner)) {
            throw new IllegalStateException("团队角色里不该有 owner");
        }
        // SQL 那份是照着词表常量手写的编译期常量：词表往上加一档时它不会自动跟上，
        // 这里盯着两边——漂了就当场炸，别等权限漏了才发现。
        if (!sqlRoles(ORG_ADMIN_ROLE_SQL).equals(ORG_ADMIN_ROLES)) {
            throw new IllegalStateException("组织管理员档位的 SQL 写法与词表对不上");
        }
    }

    /** SQL 里的角色列表（{@code 'owner', 'admin'}）还原成集合。 */
    private static Set<Role> sqlRoles(String sqlList) {
        return java.util.Arrays.stream(sqlList.split(","))
            .map(role -> role.trim().replace("'", ""))
            .map(Role::valueOf)
            .collect(Collectors.toSet());
    }

    private Roles() {}
}
