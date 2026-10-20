package com.skillmesh.api.org;

import java.util.List;
import java.util.Set;

/**
 * 角色词表：全站只有这一份。
 *
 * 两层角色——组织有 owner 这一档，团队没有：
 * 组织 owner &gt; admin &gt; publisher &gt; reviewer &gt; member &gt; viewer，
 * 团队 admin &gt; publisher &gt; reviewer &gt; member &gt; viewer。
 *
 * owner 只能由现任 owner 直接任命，不能通过邀请拿到（邀请能授的是 admin 及以下）。
 */
public final class Roles {

    /** 最高档：组织 owner（团队里没有这一档）。 */
    public static final String OWNER_ROLE = "owner";

    /** 管理员档。 */
    public static final String ADMIN_ROLE = "admin";

    /** 默认档位：邀请与「加成员」接口没给角色时用它。 */
    public static final String DEFAULT_ROLE = "member";

    /** 组织角色的等级顺序，从高到低。 */
    public static final List<String> ORG_RANK = List.of(
        OWNER_ROLE, ADMIN_ROLE, "publisher", "reviewer", DEFAULT_ROLE, "viewer");

    /** 团队角色的等级顺序，从高到低——团队里没有 owner 这一档。 */
    public static final List<String> TEAM_RANK = List.of(
        ADMIN_ROLE, "publisher", "reviewer", DEFAULT_ROLE, "viewer");

    /** 成员行能放的组织角色全集（含 owner）。 */
    public static final Set<String> ORG_ROLES = Set.copyOf(ORG_RANK);

    /** 成员行能放的团队角色全集。 */
    public static final Set<String> TEAM_ROLES = Set.copyOf(TEAM_RANK);

    /**
     * 组织管理员档位（owner 与 admin）：能管成员、邀请、团队。
     *
     * 从词表推出来，不手敲字面量——往词表里加一档新角色时权限判断自动认它。
     */
    public static final Set<String> ORG_ADMIN_ROLES = atOrAbove(ORG_RANK, ADMIN_ROLE);

    /** 组织 owner 档位：改 owner 身份只有这一档能做。 */
    public static final Set<String> ORG_OWNER_ROLES = atOrAbove(ORG_RANK, OWNER_ROLE);

    /** 团队管理员档位。 */
    public static final Set<String> TEAM_ADMIN_ROLES = atOrAbove(TEAM_RANK, ADMIN_ROLE);

    /** 邀请能授的组织角色（有序）：ORG_RANK 去掉 owner。 */
    public static final List<String> INVITABLE_ORG_RANK = ORG_RANK.stream()
        .filter(role -> !role.equals(OWNER_ROLE)).toList();

    /** 邀请能授的组织角色全集。 */
    public static final Set<String> INVITABLE_ORG_ROLES = Set.copyOf(INVITABLE_ORG_RANK);

    /**
     * @Pattern 用的正则——编译期常量，不能在运行时拼。
     * 邀请的组织角色不含 owner：owner 只能由现任 owner 直接任命。
     */
    public static final String INVITABLE_ORG_ROLE_PATTERN = "admin|publisher|reviewer|member|viewer";

    /** @Pattern 用的正则——编译期常量。 */
    public static final String TEAM_ROLE_PATTERN = "admin|publisher|reviewer|member|viewer";

    /** 词表里某档及以上的所有档位（含该档）。 */
    private static Set<String> atOrAbove(List<String> rank, String role) {
        int index = rank.indexOf(role);
        if (index < 0) {
            throw new IllegalStateException("词表里没有 " + role + " 这一档");
        }
        return Set.copyOf(rank.subList(0, index + 1));
    }

    static {
        // DTO 认正则、服务认集合，两边必须一致——曾经就分叉过一次（DTO 少了 owner）。
        if (!Set.of(INVITABLE_ORG_ROLE_PATTERN.split("\\|")).equals(INVITABLE_ORG_ROLES)
            || !Set.of(TEAM_ROLE_PATTERN.split("\\|")).equals(TEAM_ROLES)) {
            throw new IllegalStateException("角色正则与角色集合对不上");
        }
        // 档位集合必须是词表的子集，而且不能是空集——空集合会让权限判断悄悄失效。
        if (ORG_ADMIN_ROLES.isEmpty() || !ORG_ROLES.containsAll(ORG_ADMIN_ROLES)
            || ORG_OWNER_ROLES.isEmpty() || !ORG_ROLES.containsAll(ORG_OWNER_ROLES)
            || TEAM_ADMIN_ROLES.isEmpty() || !TEAM_ROLES.containsAll(TEAM_ADMIN_ROLES)
            || !ORG_ROLES.contains(DEFAULT_ROLE) || !TEAM_ROLES.contains(DEFAULT_ROLE)) {
            throw new IllegalStateException("角色档位集合与词表对不上");
        }
    }

    private Roles() {}
}
