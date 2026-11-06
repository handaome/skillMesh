package com.skillmesh.api.usage;

import com.skillmesh.api.skill.VisibleSkills;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SkillCallRecordRepository extends JpaRepository<SkillCallRecordEntity, Long> {

    /**
     * 按幂等键插入，重复的直接忽略。
     *
     * 重发不该把一次调用算成两次——统计最怕的就是这个。
     * 返回 1 表示新记了一条，0 表示这条已经报过了。
     */
    @Modifying
    @Query(value = """
        insert into skill_call_records
            (event_id, occurred_at, actor_user_id, skill_id, organization_id,
             skill_name, agent, project_path, args_preview)
        values (:eventId, :occurredAt, :actorUserId, :skillId, :organizationId,
                :skillName, :agent, :projectPath, :argsPreview)
        on conflict (event_id) do nothing
        """, nativeQuery = true)
    int insertIgnore(@Param("eventId") String eventId,
                     @Param("occurredAt") OffsetDateTime occurredAt,
                     @Param("actorUserId") UUID actorUserId,
                     @Param("skillId") UUID skillId,
                     @Param("organizationId") UUID organizationId,
                     @Param("skillName") String skillName,
                     @Param("agent") String agent,
                     @Param("projectPath") String projectPath,
                     @Param("argsPreview") String argsPreview);

    /**
     * 统计：按 skill 名聚合调用次数。
     *
     * 可见范围与审计流水同一口径：我上报的，加上我能看见的 Skill 的。
     * 个人装的 Skill 不在云端，skill_id 为空，只有上报者本人看得见。
     */
    @Query(value = """
        select r.skill_name as skill,
               count(*) as call_count,
               count(distinct r.actor_user_id) as user_count,
               max(r.occurred_at) as last_used_at
        from skill_call_records r
        where r.occurred_at >= :since
          and r.skill_name like :skillPrefix
          and (r.actor_user_id = :owner
               or exists (select 1 from skills s where s.id = r.skill_id and """
        + VisibleSkills.WHERE + """
        ))
        group by r.skill_name
        order by count(*) desc, r.skill_name
        limit cast(:limit as int)
        """, nativeQuery = true)
    List<SkillUsageStatRow> stats(@Param("owner") UUID owner,
                                  @Param("since") OffsetDateTime since,
                                  @Param("skillPrefix") String skillPrefix,
                                  @Param("limit") int limit);

    /** 明细流水，按时间倒序，够游标翻页用。 */
    @Query(value = """
        select r.* from skill_call_records r
        where (r.actor_user_id = :owner
               or exists (select 1 from skills s where s.id = r.skill_id and """
        + VisibleSkills.WHERE + """
        ))
          and (cast(:cursorTime as timestamptz) is null
               or (r.occurred_at, r.id) < (cast(:cursorTime as timestamptz),
                                           cast(:cursorId as bigint)))
        order by r.occurred_at desc, r.id desc
        limit cast(:limit as int)
        """, nativeQuery = true)
    List<SkillCallRecordEntity> findVisible(@Param("owner") UUID owner,
                                             @Param("cursorTime") OffsetDateTime cursorTime,
                                             @Param("cursorId") Long cursorId,
                                             @Param("limit") int limit);

    /**
     * 汇总：总调用次数、涉及多少个 skill、涉及多少人。
     *
     * 人数是全局去重的——统计表里每个 skill 各去重一次再相加，
     * 一个人用了三个 skill 会被算成三个人。
     */
    @Query(value = """
        select count(*) as total_calls,
               count(distinct r.skill_name) as skill_count,
               count(distinct r.actor_user_id) as user_count
        from skill_call_records r
        where r.occurred_at >= :since
          and r.skill_name like :skillPrefix
          and (r.actor_user_id = :owner
               or exists (select 1 from skills s where s.id = r.skill_id and """
        + VisibleSkills.WHERE + """
        ))
        """, nativeQuery = true)
    List<SkillUsageSummaryRow> summary(@Param("owner") UUID owner,
                                        @Param("since") OffsetDateTime since,
                                        @Param("skillPrefix") String skillPrefix);

    /** 按 slug 找我能看见的 Skill：本地 skill 名能对上云端时，好挂到组织名下统计。 */
    @Query(value = """
        select s.id as id, s.organization_id as organization_id
        from skills s
        where s.slug = :slug and """
        + VisibleSkills.WHERE + """
        order by s.updated_at desc
        limit 1
        """, nativeQuery = true)
    List<SkillRefRow> findVisibleBySlug(@Param("owner") UUID owner, @Param("slug") String slug);

    /** 统计的一行。 */
    interface SkillUsageStatRow {
        String getSkill();

        long getCallCount();

        long getUserCount();

        OffsetDateTime getLastUsedAt();
    }

    /** 匹配到的云端 Skill。 */
    interface SkillRefRow {
        UUID getId();

        UUID getOrganizationId();
    }

    /** 汇总的一行。 */
    interface SkillUsageSummaryRow {
        long getTotalCalls();

        long getSkillCount();

        long getUserCount();
    }
}
