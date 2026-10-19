package com.skillmesh.api.sync;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncStateRepository extends JpaRepository<SyncStateEntity, UUID> {

    Optional<SyncStateEntity> findByDeviceIdAndSkillIdAndLocalPath(UUID deviceId, UUID skillId,
                                                                  String localPath);
}
