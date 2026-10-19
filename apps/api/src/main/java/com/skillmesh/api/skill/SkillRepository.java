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
     * 搜索当前用户可见的 Skill。游标是 (updated_at, id) 复合键，按倒序比较，
     * 天然稳定，不会因为插入而漂移（详细设计 §0.2 要求不用深分页 offset）。
     *
     * tags 是 PG 的 text[]，HQL 里没有 `= any(...)` 写法，用 Hibernate 的
     * array_contains 函数。
     */
    @Query("""
        select s from SkillEntity s
        where (s.visibility = 'public' or s.ownerUserId = :owner)
          and (:q is null or lower(s.name) like :q or lower(s.slug) like :q
               or lower(s.description) like :q)
          and (:visibility is null or s.visibility = :visibility)
          and (:tag is null or function('array_contains', s.tags, :tag) = true)
          and s.status = 'active'
          and (:cursorTime is null or (s.updatedAt, s.id) < (:cursorTime, :cursorId))
        order by s.updatedAt desc, s.id desc
        """)
    List<SkillEntity> search(@Param("owner") UUID owner,
                             @Param("q") String q,
                             @Param("visibility") String visibility,
                             @Param("tag") String tag,
                             @Param("cursorTime") OffsetDateTime cursorTime,
                             @Param("cursorId") UUID cursorId,
                             Pageable pageable);
}
