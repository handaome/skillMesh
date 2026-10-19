package com.skillmesh.api.sync;

import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.sync.dto.SyncChangesetRequest;
import com.skillmesh.api.sync.dto.SyncChangesetResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    private final SyncService sync;
    private final CurrentUserService currentUserService;

    public SyncController(SyncService sync, CurrentUserService currentUserService) {
        this.sync = sync;
        this.currentUserService = currentUserService;
    }

    /**
     * 提交一次同步变更集。Idempotency-Key 是必带的：
     * 断网重试时同一个键必须只产生一次同步（详细设计 §3.6）。
     */
    @PostMapping("/changesets")
    public ResponseEntity<SyncChangesetResponse> submit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("X-Device-Id") UUID deviceId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SyncChangesetRequest req) {
        SyncChangesetResponse body =
            sync.submit(currentUserService.current(jwt), deviceId, idempotencyKey, req);
        return ResponseEntity.created(URI.create("/api/v1/sync/changesets/" + body.changesetId()))
            .body(body);
    }

    @GetMapping("/changesets/{changesetId}")
    public SyncChangesetResponse get(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable UUID changesetId) {
        return sync.get(currentUserService.current(jwt), changesetId);
    }
}
