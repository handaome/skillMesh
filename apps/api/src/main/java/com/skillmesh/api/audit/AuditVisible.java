package com.skillmesh.api.audit;

import com.skillmesh.api.org.Roles;

/**
 * 「当前用户能看审计流水」的 SQL 片段。
 *
 * **能看见一个 Skill，不等于能翻它的操作流水。** 团队成员能看见团队 Skill，
 * 但流水里有「谁在什么时候撤回了哪个版本」「谁把带密钥的版本发上去了」这类信息，
 * §2.5 权限矩阵把审计单列了一档：属主、组织管理员、审过它的人。
 *
 * 可见性条件（{@code VisibleSkills}）与审计权限条件（这里）是两道门：
 * 前者管「这个 Skill 归谁、谁看得见」，后者管「这个人能不能翻它的流水」。
 * 只过前者的写法让每个团队成员都能翻全队的流水。
 *
 * 片段里的表别名固定为 {@code s}（skills），参数名固定为 {@code :owner}。
 */
public final class AuditVisible {

    public static final String WHERE = """
        (s.owner_user_id = :owner
         or exists (select 1 from organization_memberships m
                    where m.organization_id = s.organization_id and m.user_id = :owner
                      and m.role in ("""
        + Roles.ORG_ADMIN_ROLE_SQL + """
        ))
         or exists (select 1 from skill_reviews r
                    join skill_versions v on v.id = r.skill_version_id
                    where v.skill_id = s.id and r.reviewer_id = :owner))""";

    private AuditVisible() {}
}
