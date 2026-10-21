package com.skillmesh.api.version;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SkillVersionRepository extends JpaRepository<SkillVersionEntity, UUID> {

    boolean existsBySkillIdAndVersion(UUID skillId, String version);

    Optional<SkillVersionEntity> findBySkillIdAndVersion(UUID skillId, String version);

    List<SkillVersionEntity> findBySkillIdOrderByCreatedAtDesc(UUID skillId);

    /**
     * 按制品反查版本。同一份内容可以被多个版本引用（内容寻址，sha256 唯一），
     * 所以这里是一对多，不能用 Optional。
     */
    List<SkillVersionEntity> findByArtifactId(UUID artifactId);

    /**
     * 待审版本：只返回「我在其所属范围里是 reviewer 及以上」的。
     *
     * 可见性与分页都在 SQL 里判：先按角色过滤、再排序、最后取 limit——
     * 反过来做（先 limit 再在内存里过滤）会漏数据，排在窗口外的待审版本永远看不到。
     *
     * 角色的取法跟 {@code OrgService.roleIn} 一致：团队成员行优先，没有才回落组织成员行。
     * 两条都查不到就没有角色——个人 Skill 也不进审核流，organization_id 为空即排除。
     *
     * @param reviewerRoles 能审的角色集合：owner/admin/publisher/reviewer
     */
    @Query(value = """
        select v.* from skill_versions v
        join skills s on s.id = v.skill_id
        where v.status = :status
          and s.organization_id is not null
          and coalesce(
                (select m.role from team_memberships m
                  where m.team_id = s.owner_team_id and m.user_id = :owner),
                (select o.role from organization_memberships o
                  where o.organization_id = s.organization_id and o.user_id = :owner)
              ) = any(string_to_array(:reviewerRoles, ','))
        order by v.created_at desc, v.id desc
        """, nativeQuery = true)
    List<SkillVersionEntity> findReviewable(@Param("owner") UUID owner,
                                             @Param("status") String status,
                                             @Param("reviewerRoles") String reviewerRoles,
                                             Pageable pageable);

    /**
     * 取版本并锁住这一行（SELECT ... FOR UPDATE）。
     *
     * 审核决定要在事务里串行化：两个人同时点「通过」时，都读到 pending_review
     * 就会各写一条 approved；「通过」与「拒绝」交错，还会把 published_by 写回 null。
     * 锁住版本行后，后一个动作只能等前一个提交完，读到的已经是 published → 409。
     *
     * 只能在事务里用（锁到事务结束才释放）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from SkillVersionEntity v where v.skillId = :skillId and v.version = :version")
    Optional<SkillVersionEntity> findBySkillIdAndVersionForUpdate(@Param("skillId") UUID skillId,
                                                                  @Param("version") String version);
}
