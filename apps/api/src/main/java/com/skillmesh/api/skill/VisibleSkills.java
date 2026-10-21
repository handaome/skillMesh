package com.skillmesh.api.skill;

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
 * 片段里的表别名固定为 {@code s}（skills），参数名固定为 {@code :owner}。
 *
 * team 可见的 Skill，团队成员与组织成员都能看——与
 * {@link SkillService#canView} 同一口径，两处必须一起改，
 * 一处改了另一处没改，搜索与直读就对不上了。
 */
public final class VisibleSkills {

    public static final String WHERE = """
        (s.visibility = 'public'
         or s.owner_user_id = :owner
         or (s.visibility = 'organization' and exists (
               select 1 from organization_memberships m
               where m.organization_id = s.organization_id and m.user_id = :owner))
         or (s.visibility = 'team' and (
               exists (select 1 from team_memberships m
                       where m.team_id = s.owner_team_id and m.user_id = :owner)
               or exists (select 1 from organization_memberships m
                       where m.organization_id = s.organization_id and m.user_id = :owner))))""";

    private VisibleSkills() {}
}
