package com.skillmesh.api.version;

import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.version.dto.CreateVersionRequest;
import com.skillmesh.api.version.dto.CreateVersionResponse;
import com.skillmesh.api.version.dto.VersionPage;
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
}
