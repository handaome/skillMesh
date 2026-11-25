package com.skillmesh.api.artifact;

import com.skillmesh.api.artifact.dto.DownloadInfo;
import com.skillmesh.api.audit.AuditOutcome;
import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.version.ArtifactAccessPolicy;
import com.skillmesh.api.version.SkillVersionEntity;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.Map;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/artifacts")
public class ArtifactController {

    private final ArtifactService artifacts;
    private final CurrentUserService currentUserService;
    private final AuditService audit;
    private final ArtifactAccessPolicy accessPolicy;

    public ArtifactController(ArtifactService artifacts, CurrentUserService currentUserService,
                              AuditService audit, ArtifactAccessPolicy accessPolicy) {
        this.artifacts = artifacts;
        this.currentUserService = currentUserService;
        this.audit = audit;
        this.accessPolicy = accessPolicy;
    }

    @GetMapping("/{artifactId}/download")
    public ResponseEntity<?> download(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable UUID artifactId,
                                      @RequestParam(defaultValue = "redirect") String disposition) {
        CurrentUser user = currentUserService.current(jwt);
        // 版本状态也要看：撤回与隔离的版本，制品不该再被取走
        // （设计文档 §10.1）。判在服务层，这里只管调。
        // 门禁把这张制品和放行它的那一版都查出来了，直接接着用，别再查一遍。
        ArtifactAccessPolicy.DownloadGrant grant = accessPolicy.requireDownloadable(user, artifactId);
        ArtifactEntity artifact = grant.artifact();

        if ("stream".equals(disposition)) {
            // 名额先拿到再留痕：拿不到就 429，不能记成一次成功的下载。
            InputStream in = artifacts.openDownload(artifact);
            recordDownload(user, grant.version());
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(artifact.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + fileName(artifact) + "\"")
                .contentLength(artifact.getSizeBytes())
                .body(new InputStreamResource(in));
        }
        recordDownload(user, grant.version());
        DownloadInfo info = new DownloadInfo(artifacts.presignedDownload(artifact),
            artifact.getSha256(), artifact.getSizeBytes(), artifact.getContentType(),
            OffsetDateTime.now(ZoneOffset.UTC).plus(ArtifactService.DOWNLOAD_TTL));
        return ResponseEntity.ok(info);
    }

    /**
     * 下载留痕：谁在什么时候取走了哪份制品、属于哪个版本。
     *
     * 记的是门禁放行的那一版——同一份内容被多个版本引用时，
     * 「第一个引用它的版本」未必是这次真正授权下载的那一版。
     */
    private void recordDownload(CurrentUser user, SkillVersionEntity version) {
        audit.record(user, "artifact.download", "skill", version.getSkillId(), AuditOutcome.success,
            Map.of("version", version.getVersion(), "artifact_id", version.getArtifactId().toString()));
    }

    private static String fileName(ArtifactEntity artifact) {
        String key = artifact.getStorageKey();
        return key.substring(key.lastIndexOf('/') + 1);
    }
}
