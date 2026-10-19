package com.skillmesh.api.install;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstallationRepository extends JpaRepository<InstallationEntity, UUID> {

    List<InstallationEntity> findByUserIdOrderByLastUpdatedAtDesc(UUID userId);

    List<InstallationEntity> findByUserIdAndStatusOrderByLastUpdatedAtDesc(UUID userId, String status);

    Optional<InstallationEntity> findByIdAndUserId(UUID id, UUID userId);

    Optional<InstallationEntity> findByUserIdAndDeviceIdAndTargetPathAndSkillId(
        UUID userId, UUID deviceId, String targetPath, UUID skillId);
}
