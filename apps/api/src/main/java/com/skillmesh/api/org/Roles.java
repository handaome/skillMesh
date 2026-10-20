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

    /** 组织角色的等级顺序，从高到低。 */
    public static final List<String> ORG_RANK = List.of(
        "owner", "admin", "publisher", "reviewer", "member", "viewer");

    /** 团队角色的等级顺序，从高到低——团队里没有 owner 这一档。 */
    public static final List<String> TEAM_RANK = List.of(
        "admin", "publisher", "reviewer", "member", "viewer");

    /** 成员行能放的组织角色全集（含 owner）。 */
    public static final Set<String> ORG_ROLES = Set.copyOf(ORG_RANK);

    /** 成员行能放的团队角色全集。 */
    public static final Set<String> TEAM_ROLES = Set.copyOf(TEAM_RANK);

    /** 邀请能授的组织角色（有序）：ORG_RANK 去掉 owner。 */
    public static final List<String> INVITABLE_ORG_RANK = ORG_RANK.stream()
        .filter(role -> !role.equals("owner")).toList();

    /** 邀请能授的组织角色全集。 */
    public static final Set<String> INVITABLE_ORG_ROLES = Set.copyOf(INVITABLE_ORG_RANK);

    /**
     * @Pattern 用的正则——编译期常量，不能在运行时拼。
     * 邀请的组织角色不含 owner：owner 只能由现任 owner 直接任命。
     */
    public static final String INVITABLE_ORG_ROLE_PATTERN = "admin|publisher|reviewer|member|viewer";

    /** @Pattern 用的正则——编译期常量。 */
    public static final String TEAM_ROLE_PATTERN = "admin|publisher|reviewer|member|viewer";

    static {
        // DTO 认正则、服务认集合，两边必须一致——曾经就分叉过一次（DTO 少了 owner）。
        if (!Set.of(INVITABLE_ORG_ROLE_PATTERN.split("\\|")).equals(INVITABLE_ORG_ROLES)
            || !Set.of(TEAM_ROLE_PATTERN.split("\\|")).equals(TEAM_ROLES)) {
            throw new IllegalStateException("角色正则与角色集合对不上");
        }
    }

    private Roles() {}
}
