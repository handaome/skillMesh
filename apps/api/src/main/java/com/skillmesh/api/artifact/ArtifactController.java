package com.skillmesh.api.artifact;

import com.skillmesh.api.artifact.dto.DownloadInfo;
import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SkillService;
import com.skillmesh.api.version.SkillVersionEntity;
import com.skillmesh.api.version.SkillVersionRepository;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.List;
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
    private final SkillVersionRepository versions;
    private final SkillRepository skills;
    private final CurrentUserService currentUserService;
    private final AuditService audit;
    private final SkillService skillsService;

    public ArtifactController(ArtifactService artifacts, SkillVersionRepository versions,
                              SkillRepository skills, CurrentUserService currentUserService,
                              AuditService audit, SkillService skillsService) {
        this.artifacts = artifacts;
        this.versions = versions;
        this.skills = skills;
        this.currentUserService = currentUserService;
        this.audit = audit;
        this.skillsService = skillsService;
    }

    @GetMapping("/{artifactId}/download")
    public ResponseEntity<?> download(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable UUID artifactId,
                                      @RequestParam(defaultValue = "redirect") String disposition) {
        CurrentUser user = currentUserService.current(jwt);
        ArtifactEntity artifact = artifacts.require(artifactId);
        requireVisible(user, artifact);
        recordDownload(user, artifactId);

        if ("stream".equals(disposition)) {
            InputStream in = artifacts.open(artifact);
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(artifact.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + fileName(artifact) + "\"")
                .contentLength(artifact.getSizeBytes())
                .body(new InputStreamResource(in));
        }
        DownloadInfo info = new DownloadInfo(artifacts.presignedDownload(artifact),
            artifact.getSha256(), artifact.getSizeBytes(), artifact.getContentType(),
            OffsetDateTime.now(ZoneOffset.UTC).plus(ArtifactService.DOWNLOAD_TTL));
        return ResponseEntity.ok(info);
    }

    /**
     * 制品只要被任何一个调用者可见的版本引用就允许下载。
     * 同一份内容可能被多个版本引用（内容寻址，sha256 唯一），
     * 所以要遍历全部引用者，不能只看第一个。
     */
    private void requireVisible(CurrentUser user, ArtifactEntity artifact) {
        List<SkillVersionEntity> referencing = versions.findByArtifactId(artifact.getId());
        if (referencing.isEmpty()) {
            throw ApiException.notFound("Artifact");
        }
        for (SkillVersionEntity version : referencing) {
            SkillEntity skill = skills.findById(version.getSkillId()).orElse(null);
            if (skill != null && skillsService.canView(user, skill)) {
                return;
            }
        }
        throw ApiException.notFound("Artifact");
    }

    /** 下载留痕：谁在什么时候取走了哪份制品。 */
    private void recordDownload(CurrentUser user, UUID artifactId) {
        for (SkillVersionEntity version : versions.findByArtifactId(artifactId)) {
            audit.record(user, "artifact.download", "skill", version.getSkillId(), "success",
                Map.of("version", version.getVersion(), "artifact_id", artifactId.toString()));
            return;
        }
    }

    private static String fileName(ArtifactEntity artifact) {
        String key = artifact.getStorageKey();
        return key.substring(key.lastIndexOf('/') + 1);
    }
}
