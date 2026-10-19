package com.skillmesh.api.upload;

import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.version.dto.UploadSession;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    @PostMapping("/{uploadId}/complete")
    public ResponseEntity<UploadSession> complete(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable UUID uploadId) {
        UploadSession session = uploads.complete(currentUserService.current(jwt), uploadId);
        return ResponseEntity.accepted().body(session);
    }
}
