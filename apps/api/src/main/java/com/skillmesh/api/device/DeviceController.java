package com.skillmesh.api.device;

import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.device.dto.RegisterDeviceRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceService devices;
    private final CurrentUserService currentUserService;

    public DeviceController(DeviceService devices, CurrentUserService currentUserService) {
        this.devices = devices;
        this.currentUserService = currentUserService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> register(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RegisterDeviceRequest req) {
        DeviceEntity device = devices.register(currentUserService.current(jwt), req.device_key(),
            req.display_name(), req.platform(), req.cli_version());
        return ResponseEntity.created(URI.create("/api/v1/devices/" + device.getId()))
            .body(toResponse(device));
    }

    @DeleteMapping("/{deviceId}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable UUID deviceId) {
        devices.revoke(currentUserService.current(jwt), deviceId);
        return ResponseEntity.noContent().build();
    }

    static Map<String, Object> toResponse(DeviceEntity device) {
        OffsetDateTime lastSeen = device.getLastSeenAt();
        return Map.of(
            "id", device.getId().toString(),
            "device_key", device.getDeviceKey(),
            "display_name", device.getDisplayName(),
            "platform", device.getPlatform(),
            "cli_version", device.getCliVersion(),
            "last_seen_at", lastSeen == null ? "" : lastSeen.toString(),
            "revoked", device.getRevokedAt() != null);
    }
}
