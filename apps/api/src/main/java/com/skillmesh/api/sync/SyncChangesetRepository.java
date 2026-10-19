package com.skillmesh.api.sync;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncChangesetRepository extends JpaRepository<SyncChangesetEntity, UUID> {

    Optional<SyncChangesetEntity> findByDeviceIdAndIdempotencyKey(UUID deviceId, String key);
}
