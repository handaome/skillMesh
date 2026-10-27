package com.skillmesh.api.skill;

import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.common.CursorPage;
import com.skillmesh.api.skill.dto.CreateSkillRequest;
import com.skillmesh.api.skill.dto.SkillResponse;
import com.skillmesh.api.skill.dto.UpdateSkillRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
    public CursorPage<SkillResponse> list(@AuthenticationPrincipal Jwt jwt,
                          @RequestParam(required = false) String q,
                          @RequestParam(required = false) String visibility,
                          @RequestParam(required = false) String tag,
                          @RequestParam(defaultValue = "false") boolean mine,
                          @RequestParam(required = false) Integer limit,
                          @RequestParam(required = false) String cursor) {
        return skills.search(currentUserService.current(jwt), q, visibility, tag, mine, limit, cursor);
    }

    @GetMapping("/{skillId}")
    public SkillResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID skillId) {
        return skills.get(currentUserService.current(jwt), skillId);
    }

    /**
     * 改名称、描述、标签。slug 与可见性不在这里改——那会影响别人已经装的东西。
     */
    @PatchMapping("/{skillId}")
    public SkillResponse update(@AuthenticationPrincipal Jwt jwt,
                                @PathVariable UUID skillId,
                                @Valid @RequestBody UpdateSkillRequest req) {
        return skills.update(currentUserService.current(jwt), skillId, req);
    }

    /**
     * 删除一个 Skill。
     *
     * 软删除：数据留着，status 改成 archived，回收期内能还原。
     * 客户端同步时据此把本地那份一起删掉。
     */
    @DeleteMapping("/{skillId}")
    public ResponseEntity<Void> archive(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable UUID skillId) {
        skills.archive(currentUserService.current(jwt), skillId);
        return ResponseEntity.noContent().build();
    }

    /** 还原被删除的 Skill。 */
    @PostMapping("/{skillId}/restore")
    public SkillResponse restore(@AuthenticationPrincipal Jwt jwt,
                                 @PathVariable UUID skillId) {
        return skills.restore(currentUserService.current(jwt), skillId);
    }
}
