package com.skillmesh.api.invite;

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

public interface InvitationRepository extends JpaRepository<InvitationEntity, UUID> {

    Optional<InvitationEntity> findByTokenHash(String tokenHash);

    /**
     * 锁住邀请行（SELECT ... FOR UPDATE）。
     *
     * 接受邀请是「读状态 → 写成员行 → 翻状态」，不锁的话两个并发请求会同时读到
     * pending、同时写成员关系——一份单次令牌把两个人拉进组织。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InvitationEntity i where i.tokenHash = :tokenHash")
    Optional<InvitationEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * 组织下的邀请分页（倒序）。
     *
     * 游标是 (created_at, id) 复合键：created_at 会重复（同一秒建的邀请），
     * 单靠它翻页会重不漏不了——所以拿 id 兜底排序，跟技能列表同一套口径。
     *
     * 用原生查询：游标的元组比较是 PostgreSQL 专有写法，HQL 没有对应写法。
     * 可空参数显式 cast：只出现在 `is null` 里的占位符 PostgreSQL 推不出类型。
     */
    @Query(value = """
        select i.* from org_invitations i
        where i.organization_id = :organizationId
          and (cast(:cursorTime as timestamptz) is null
               or (i.created_at, i.id) < (:cursorTime, :cursorId))
        order by i.created_at desc, i.id desc
        """, nativeQuery = true)
    List<InvitationEntity> pageByOrganizationId(@Param("organizationId") UUID organizationId,
                                                 @Param("cursorTime") OffsetDateTime cursorTime,
                                                 @Param("cursorId") UUID cursorId,
                                                 Pageable pageable);
}
