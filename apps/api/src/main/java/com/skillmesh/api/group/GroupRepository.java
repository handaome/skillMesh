package com.skillmesh.api.group;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupRepository extends JpaRepository<GroupEntity, UUID> {

    /**
     * 可见的集合。
     *
     * 用原生查询：可见性片段里有 PostgreSQL 专有的 exists 子查询，
     * 游标排序也在 SQL 里做。
     */
    @Query(value = """
        select g.* from skill_groups g
        where """ + VisibleGroups.WHERE + """
          and g.status = 'active'
        order by g.updated_at desc, g.id desc
        """, nativeQuery = true)
    List<GroupEntity> findVisible(@Param("owner") UUID owner);

    @Query(value = """
        select g.* from skill_groups g
        where g.id = :id and g.status = 'active'
        """, nativeQuery = true)
    Optional<GroupEntity> findActiveById(@Param("id") UUID id);

    /** 个人空间里有没有这个 slug 的 active 集合。 */
    @Query(value = """
        select case when count(g) > 0 then true else false end from skill_groups g
        where g.owner_user_id = :owner and g.organization_id is null
          and g.slug = :slug and g.status = 'active'
        """, nativeQuery = true)
    boolean existsPersonalSlug(@Param("owner") UUID owner, @Param("slug") String slug);

    /** 组织空间里有没有这个 slug 的 active 集合。 */
    @Query(value = """
        select case when count(g) > 0 then true else false end from skill_groups g
        where g.organization_id = :organizationId
          and g.slug = :slug and g.status = 'active'
        """, nativeQuery = true)
    boolean existsOrganizationSlug(@Param("organizationId") UUID organizationId,
                                   @Param("slug") String slug);

    /** 按 slug 取一个可见的集合：CLI 与网页端都按 slug 引用集合。 */
    @Query(value = """
        select g.* from skill_groups g
        where g.slug = :slug and g.status = 'active'
          and """ + VisibleGroups.WHERE
        + """
        order by g.updated_at desc, g.id desc
        limit 1
        """, nativeQuery = true)
    Optional<GroupEntity> findVisibleBySlug(@Param("owner") UUID owner,
                                           @Param("slug") String slug);
}
