package com.skillmesh.api.org;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrgRepository extends JpaRepository<OrganizationEntity, UUID> {

    Optional<OrganizationEntity> findBySlug(String slug);

    /**
     * 锁住组织行（SELECT ... FOR UPDATE）——组织内的角色变更串行化，
     * owner 计数才不会在并发降级时读到旧值。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrganizationEntity o where o.id = :id")
    Optional<OrganizationEntity> findByIdForUpdate(@Param("id") UUID id);
}
