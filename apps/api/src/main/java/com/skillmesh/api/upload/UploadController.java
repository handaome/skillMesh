package com.skillmesh.api.upload;

import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.version.dto.UploadSession;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {

    private final UploadService uploads;
    private final CurrentUserService currentUserService;

    public UploadController(UploadService uploads, CurrentUserService currentUserService) {
        this.uploads = uploads;
        this.currentUserService = currentUserService;
    }

    /**
     * 接收上传内容并写入临时对象。
     *
     * 客户端只连 API，不直连 MinIO——预签名地址里的 MinIO 主机名
     * 在容器外解析不了（部署时 MinIO 通常只在内网）。
     */
    @PutMapping("/{uploadId}")
    public ResponseEntity<Void> store(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable UUID uploadId,
                                      @RequestHeader(value = "Content-Type",
                                          defaultValue = "application/octet-stream") String contentType,
                                      @RequestHeader(value = "Content-Length", required = false)
                                      Long length,
                                      HttpServletRequest request) throws IOException {
        uploads.store(currentUserService.current(jwt), uploadId, request.getInputStream(),
            length == null ? -1 : length, contentType);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{uploadId}/complete")
    public ResponseEntity<UploadSession> complete(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable UUID uploadId) {
        UploadSession session = uploads.complete(currentUserService.current(jwt), uploadId);
        return ResponseEntity.accepted().body(session);
    }
}
