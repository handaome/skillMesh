package com.skillmesh.api.version;

import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.version.dto.RevokeVersionRequest;
import com.skillmesh.api.version.dto.SkillVersionResponse;
import com.skillmesh.api.version.dto.CreateVersionRequest;
import com.skillmesh.api.version.dto.CreateVersionResponse;
import com.skillmesh.api.version.dto.VersionPage;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SkillVersionController {

    private final SkillVersionService versions;
    private final CurrentUserService currentUserService;

    public SkillVersionController(SkillVersionService versions,
                                  CurrentUserService currentUserService) {
        this.versions = versions;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/skills/{skillId}/versions")
    public ResponseEntity<CreateVersionResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID skillId,
            @Valid @RequestBody CreateVersionRequest req) {
        CreateVersionResponse body = versions.create(currentUserService.current(jwt), skillId, req);
        return ResponseEntity.created(
            URI.create("/api/v1/skills/" + skillId + "/versions/" + body.versionId()))
            .body(body);
    }

    @GetMapping("/skills/{skillId}/versions")
    public VersionPage list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID skillId) {
        return versions.list(currentUserService.current(jwt), skillId);
    }

    /**
     * 撤回一个版本（设计文档 §10 撤回）。
     * 只改可用状态、留下记录；已经装在别人机器上的内容不无痕删除。
     */
    @GetMapping("/skills/{skillId}/versions/{version}")
    public SkillVersionResponse detail(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable UUID skillId,
                                      @PathVariable String version) {
        return versions.detail(currentUserService.current(jwt), skillId, version);
    }

    @PostMapping("/skills/{skillId}/versions/{version}/revoke")
    public ResponseEntity<Map<String, Object>> revoke(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID skillId,
            @PathVariable String version,
            @RequestBody(required = false) RevokeVersionRequest req) {
        return ResponseEntity.ok(versions.revoke(currentUserService.current(jwt), skillId,
            version, req == null ? "" : req.reason()));
    }
}
