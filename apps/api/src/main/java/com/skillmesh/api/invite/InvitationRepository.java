package com.skillmesh.api.invite;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    List<InvitationEntity> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);
}
