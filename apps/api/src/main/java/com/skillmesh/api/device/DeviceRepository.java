package com.skillmesh.api.device;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<DeviceEntity, UUID> {

    Optional<DeviceEntity> findByUserIdAndDeviceKey(UUID userId, String deviceKey);

    Optional<DeviceEntity> findByIdAndUserId(UUID id, UUID userId);
}
