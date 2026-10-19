package com.skillmesh.api.sync;

import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.sync.dto.SyncChangesetRequest;
import com.skillmesh.api.sync.dto.SyncChangesetResponse;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    private final SyncService sync;
    private final SyncUploadService syncUploads;
    private final CurrentUserService currentUserService;

    public SyncController(SyncService sync, SyncUploadService syncUploads,
                          CurrentUserService currentUserService) {
        this.sync = sync;
        this.syncUploads = syncUploads;
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

    /**
     * 标记冲突已解决（详细设计 §12）。
     * 只改记录状态——真正覆盖的是客户端自己的文件，服务端不碰。
     */
    @PostMapping("/changesets/{changesetId}/resolve")
    public SyncChangesetResponse resolve(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable UUID changesetId,
                                        @RequestParam(defaultValue = "keep-local") String resolution) {
        return sync.resolve(currentUserService.current(jwt), changesetId, resolution);
    }

    /** 为变更集里的文件开上传会话（详细设计 §2.4.2 第 2 步）。 */
    @PostMapping("/changesets/{changesetId}/uploads")
    public List<SyncUploadService.UploadSlot> openUploads(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID changesetId,
            @RequestBody @Valid List<SyncUploadService.FileSpec> files) {
        return syncUploads.openUploads(currentUserService.current(jwt), changesetId, files);
    }

    /** 提交变更集：基线对得上就生成新版本，对不上转冲突（§2.4.2 第 4-6 步）。 */
    @PostMapping("/changesets/{changesetId}/commit")
    public Map<String, Object> commit(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable UUID changesetId,
                                      @RequestBody(required = false) CommitRequest req) {
        try {
            return syncUploads.commit(currentUserService.current(jwt), changesetId,
                req == null ? null : req.expected_revision());
        } catch (IOException e) {
            throw new ApiException(500, ErrorCodes.INTERNAL, "提交变更集失败：" + e.getMessage());
        }
    }

    @GetMapping("/changesets/{changesetId}")
    public SyncChangesetResponse get(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable UUID changesetId) {
        return sync.get(currentUserService.current(jwt), changesetId);
    }

    public record CommitRequest(String expected_revision) {}
}
