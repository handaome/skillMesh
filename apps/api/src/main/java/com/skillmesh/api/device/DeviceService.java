package com.skillmesh.api.device;

import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.auth.CurrentUser;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 设备注册。同一台机器重复注册返回同一台，不制造幽灵设备。 */
@Service
public class DeviceService {

    private final DeviceRepository devices;
    private final AuditService audit;

    public DeviceService(DeviceRepository devices, AuditService audit) {
        this.devices = devices;
        this.audit = audit;
    }

    @Transactional
    public DeviceEntity register(CurrentUser user, String deviceKey, String displayName,
                                 String platform, String cliVersion) {
        DeviceEntity device = devices.findByUserIdAndDeviceKey(user.id(), deviceKey)
            .orElseGet(() -> {
                DeviceEntity fresh = new DeviceEntity();
                fresh.setId(UUID.randomUUID());
                fresh.setUserId(user.id());
                fresh.setDeviceKey(deviceKey);
                fresh.setDisplayName(displayName == null ? "" : displayName);
                fresh.setPlatform(platform == null ? "unknown" : platform);
                fresh.setCliVersion(cliVersion == null ? "" : cliVersion);
                fresh.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                return devices.save(fresh);
            });
        device.setLastSeenAt(OffsetDateTime.now(ZoneOffset.UTC));
        if (cliVersion != null && !cliVersion.isBlank()) {
            device.setCliVersion(cliVersion);
        }
        devices.save(device);
        return device;
    }

    /** 取设备并顺手续上 last_seen；不是这台用户的设备按 404 处理。 */
    @Transactional
    public DeviceEntity require(CurrentUser user, UUID deviceId) {
        DeviceEntity device = devices.findByIdAndUserId(deviceId, user.id())
            .orElseThrow(() -> com.skillmesh.api.common.ApiException.notFound("设备"));
        device.setLastSeenAt(OffsetDateTime.now(ZoneOffset.UTC));
        devices.save(device);
        return device;
    }

    @Transactional
    public void revoke(CurrentUser user, UUID deviceId) {
        DeviceEntity device = require(user, deviceId);
        device.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
        devices.save(device);
        audit.record(user, "device.revoke", "device", deviceId, "success", Map.of());
    }
}
