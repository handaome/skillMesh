package com.skillmesh.api.audit;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditEventEntity, Long> {

    List<AuditEventEntity> findByTargetTypeAndTargetIdOrderByOccurredAtDesc(
        String targetType, String targetId, Pageable pageable);
}
