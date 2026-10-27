package com.skillmesh.api.skill;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SkillRepository extends JpaRepository<SkillEntity, UUID> {

    /**
     * 取 Skill 并锁住这一行（SELECT ... FOR UPDATE）。
     *
     * 发版要更新 latest_version：不锁的话两个并发发布会各读各的
     * latest_version，后写的把先写的顶掉——latest 退回到旧版本。
     * 锁的顺序全局统一为「先 Skill 行、后版本行」，两条路径一致才不会死锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SkillEntity s where s.id = :id")
    Optional<SkillEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * 个人空间里有没有这个 slug 的 active Skill。
     *
     * 只看 active：归档（删除）的 Skill 让出了 slug，可以重新建一个同名的。
     *
     * excludeId 是要排除的那个：还原时排除自己，否则它自己就把自己挡住了。
     */
    @Query("select case when count(s) > 0 then true else false end from SkillEntity s "
        + "where s.ownerUserId = :owner and s.organizationId is null "
        + "and s.slug = :slug and s.status = :status "
        + "and (:excludeId is null or s.id <> :excludeId)")
    boolean existsInPersonalSpace(@Param("owner") UUID owner,
                                  @Param("slug") String slug,
                                  @Param("status") String status,
                                  @Param("excludeId") UUID excludeId);

    /**
     * 组织空间里有没有这个 slug 的 active Skill。与个人空间同一口径。
     *
     * 对应唯一索引 uq_skills_org_slug：同一个组织里 slug 是唯一的，
     * 团队之间也共用这一个命名空间。
     */
    @Query("select case when count(s) > 0 then true else false end from SkillEntity s "
        + "where s.organizationId = :organizationId "
        + "and s.slug = :slug and s.status = :status "
        + "and (:excludeId is null or s.id <> :excludeId)")
    boolean existsInOrganization(@Param("organizationId") UUID organizationId,
                                 @Param("slug") String slug,
                                 @Param("status") String status,
                                 @Param("excludeId") UUID excludeId);

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
        where """ + VisibleSkills.WHERE + """
          and (cast(:q as text) is null or lower(s.name) like :q
               or lower(s.slug) like :q or lower(s.description) like :q)
          and (cast(:visibility as text) is null or s.visibility = :visibility)
          and (cast(:tag as text) is null or s.tags @> ARRAY[cast(:tag as text)])
          and (not cast(:mine as boolean) or s.owner_user_id = :owner)
          and s.status = """ + SkillStatus.ACTIVE_SQL + """
          and (cast(:cursorTime as timestamptz) is null
               or (s.updated_at, s.id) < (:cursorTime, :cursorId))
        order by s.updated_at desc, s.id desc
        """, nativeQuery = true)
    List<SkillEntity> search(@Param("owner") UUID owner,
                             @Param("q") String q,
                             @Param("visibility") String visibility,
                             @Param("tag") String tag,
                             @Param("mine") boolean mine,
                             @Param("cursorTime") OffsetDateTime cursorTime,
                             @Param("cursorId") UUID cursorId,
                             Pageable pageable);

    /**
     * 列出已归档（删除）的 Skill。
     *
     * 归档的 Skill 对普通路径不可见：搜索只返回 active，列表接口也走同一口径。
     * 但「删过哪些」要能被本人看见——客户端据此把本地那份一起删掉，
     * 人也要能看见自己删了什么、把它找回来。
     *
     * 范围是「我删过的」：owner_user_id 是本人。删团队 Skill 也要有 publisher 资格，
     * 所以本人删掉的一定在列表里；别人删的团队 Skill 不在这儿出现。
     *
     * 排序用 updated_at：归档时它被刷新，与删除时间同序，
     * 游标还是那个 (updated_at, id) 复合键，不用另起一套。
     */
    @Query(value = """
        select s.* from skills s
        where s.owner_user_id = :owner
          and s.status = """ + SkillStatus.ARCHIVED_SQL + """
          and (cast(:cursorTime as timestamptz) is null
               or (s.updated_at, s.id) < (:cursorTime, :cursorId))
        order by s.updated_at desc, s.id desc
        """, nativeQuery = true)
    List<SkillEntity> findArchived(@Param("owner") UUID owner,
                                    @Param("cursorTime") OffsetDateTime cursorTime,
                                    @Param("cursorId") UUID cursorId,
                                    Pageable pageable);
}
