package com.skillmesh.api.skill;

import com.skillmesh.api.org.Roles;

/**
 * 「当前用户可见的 Skill」条件片段。
 *
 * 搜索与审计流水查询共用同一份：可见性是安全边界，
 * 两处各写一份迟早漂移——而且漂移的方向通常是漏权限。
 *
 * 用 exists 子查询而不是把「我加入的组织/团队 id 数组」传进来：
 * 数组要额外处理空集合（PG 的 {@code = any('{}')} 虽然合法但绕），
 * 子查询让数据库自己判，SQL 也更直白。
 *
 * 片段里的表别名固定为 {@code g}（skill_groups），参数名固定为 {@code :owner}。
 *
 * team 可见的 Skill：该团队成员，或该组织的 owner/admin 能看——与
 * {@link SkillService#canView} 同一口径，两处必须一起改，
 * 一处改了另一处没改，搜索与直读就对不上了。
 * 组织里的普通成员看不到别的团队的 Skill：团队可见归团队管，
 * 放行到组织管理员为止（他们在审核队列里能批，「能批不能看」说不通）。
 *
 * 组织管理员档位取 {@link Roles#ORG_ADMIN_ROLE_SQL}——原生 SQL 的片段必须是
 * 编译期常量，集合拼不出常量表达式，那份列表只能照着词表手写一份。
 */
public final class VisibleGroups {

    public static final String WHERE = """
        (g.visibility = 'public'
         or g.owner_user_id = :owner
         or (g.visibility = 'organization' and exists (
               select 1 from organization_memberships m
               where m.organization_id = g.organization_id and m.user_id = :owner))
         or (g.visibility = 'team' and (
               exists (select 1 from team_memberships m
                       where m.team_id = g.owner_team_id and m.user_id = :owner)
               or exists (select 1 from organization_memberships m
                       where m.organization_id = g.organization_id and m.user_id = :owner
                         and m.role in ("""
        + Roles.ORG_ADMIN_ROLE_SQL + ")))))";

    private VisibleGroups() {}
}
