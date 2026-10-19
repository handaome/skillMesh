package com.skillmesh.api.skill;

import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.skill.dto.CreateSkillRequest;
import com.skillmesh.api.skill.dto.SkillPage;
import com.skillmesh.api.skill.dto.SkillResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {

    private final SkillService skills;
    private final CurrentUserService currentUserService;

    public SkillController(SkillService skills, CurrentUserService currentUserService) {
        this.skills = skills;
        this.currentUserService = currentUserService;
    }

    @PostMapping
    public ResponseEntity<SkillResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody CreateSkillRequest req) {
        SkillResponse created = skills.create(currentUserService.current(jwt), req);
        return ResponseEntity.created(URI.create("/api/v1/skills/" + created.id())).body(created);
    }

    @GetMapping
    public SkillPage list(@AuthenticationPrincipal Jwt jwt,
                          @RequestParam(required = false) String q,
                          @RequestParam(required = false) String visibility,
                          @RequestParam(required = false) String tag,
                          @RequestParam(required = false) Integer limit,
                          @RequestParam(required = false) String cursor) {
        return skills.search(currentUserService.current(jwt), q, visibility, tag, limit, cursor);
    }

    @GetMapping("/{skillId}")
    public SkillResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID skillId) {
        return skills.get(currentUserService.current(jwt), skillId);
    }
}
