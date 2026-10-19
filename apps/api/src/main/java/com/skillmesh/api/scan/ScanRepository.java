package com.skillmesh.api.scan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScanRepository extends JpaRepository<ScanEntity, UUID> {

    /** 取这个版本最近一次扫描结论。 */
    Optional<ScanEntity> findFirstBySkillVersionIdOrderByStartedAtDesc(UUID skillVersionId);

    List<ScanEntity> findBySkillVersionIdOrderByStartedAtDesc(UUID skillVersionId);
}
