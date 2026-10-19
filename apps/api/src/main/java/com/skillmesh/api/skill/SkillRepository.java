package com.skillmesh.api.skill;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SkillRepository extends JpaRepository<SkillEntity, UUID> {

    boolean existsByOwnerUserIdAndOrganizationIdIsNullAndSlug(UUID ownerUserId, String slug);

    /**
     * 搜索当前用户可见的 Skill。
     *
     * 用原生查询而不是 JPQL：tags 的数组包含（= any）与游标的元组比较
     * （updated_at, id）都是 PostgreSQL 专有语法，HQL 没有对应写法。
     *
     * 可空参数一律显式 cast：Spring Data 会把同名参数的每次出现展开成独立的
     * 占位符，而只出现在 `is null` 里的那个占位符 PostgreSQL 推不出类型
     * （could not determine data type of parameter）。
     *
     * 游标是 (updated_at, id) 复合键，倒序比较，天然稳定，不会因为插入而漂移
     * （详细设计 §0.2 要求不用会漂移的深分页 offset）。
     */
    @Query(value = """
        select s.* from skills s
        where (s.visibility = 'public' or s.owner_user_id = :owner)
          and (cast(:q as text) is null or lower(s.name) like :q
               or lower(s.slug) like :q or lower(s.description) like :q)
          and (cast(:visibility as text) is null or s.visibility = :visibility)
          and (cast(:tag as text) is null or :tag = any(s.tags))
          and s.status = 'active'
          and (cast(:cursorTime as timestamptz) is null
               or (s.updated_at, s.id) < (:cursorTime, :cursorId))
        order by s.updated_at desc, s.id desc
        """, nativeQuery = true)
    List<SkillEntity> search(@Param("owner") UUID owner,
                             @Param("q") String q,
                             @Param("visibility") String visibility,
                             @Param("tag") String tag,
                             @Param("cursorTime") OffsetDateTime cursorTime,
                             @Param("cursorId") UUID cursorId,
                             Pageable pageable);
}
