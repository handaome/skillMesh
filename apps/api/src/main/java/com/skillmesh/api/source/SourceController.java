package com.skillmesh.api.source;

import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.source.dto.ImportFromGitRequest;
import com.skillmesh.api.source.dto.ImportFromGitResponse;
import com.skillmesh.api.source.dto.ResolveSourceRequest;
import com.skillmesh.api.source.dto.UpdateCheckResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Git 来源解析与更新检查（设计文档 §12）。 */
@RestController
@RequestMapping("/api/v1/sources")
public class SourceController {

    private final SourceService sources;
    private final GitSourceImportService imports;
    private final CurrentUserService currentUserService;
    private final GitHostPolicy policy;

    public SourceController(SourceService sources, GitSourceImportService imports,
                            CurrentUserService currentUserService, GitHostPolicy policy) {
        this.sources = sources;
        this.imports = imports;
        this.currentUserService = currentUserService;
        this.policy = policy;
    }

    @PostMapping("/resolve")
    public ResponseEntity<Map<String, Object>> resolve(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ResolveSourceRequest req) {
        currentUserService.current(jwt);
        SourceService.ResolveResult result =
            sources.resolve(req.repositoryUrl(), req.path(), req.ref());
        return ResponseEntity.ok(Map.of(
            "repository_url", result.repositoryUrl(),
            "head_commit", result.headCommit(),
            "candidates", result.candidates().stream()
                .map(candidate -> Map.of("path", candidate.path(), "name", candidate.name()))
                .toList()));
    }

    @PostMapping("/check-updates")
    public UpdateCheckResponse checkUpdates(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ResolveSourceRequest req) {
        currentUserService.current(jwt);
        SourceService.UpdateCheck check = sources.checkUpdates(req.repositoryUrl(), req.ref());
        return new UpdateCheckResponse(check.repositoryUrl(), check.ref(), check.commit(),
            policy.allowedHosts());
    }

    /**
     * 从 Git 仓库导入一个 Skill 到云端。
     *
     * 给的是那些「从 GitHub 下载了、还没进 SkillMesh」的手工 Skill：
     * 导入后它在云端有来源记录，之后就能在网页上查更新、拉新版。
     */
    @PostMapping("/import")
    public ImportFromGitResponse importSkill(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ImportFromGitRequest req) {
        return imports.importSkill(currentUserService.current(jwt), req);
    }
}
