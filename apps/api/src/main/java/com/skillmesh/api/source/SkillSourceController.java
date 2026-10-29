package com.skillmesh.api.source;

import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.source.dto.SkillSourceResponse;
import com.skillmesh.api.source.dto.SourceUpdateCheckResponse;
import com.skillmesh.api.source.dto.SourceUpdateResultResponse;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 按 Skill 维度操作它的 Git 来源：看来源、查更新、拉新版。
 *
 * 与 {@link SourceController} 的分工：那套按仓库地址解析（"这个仓库里有什么"），
 * 这套按 Skill 走（"我名下这个 Skill 的来源是它，有没有新版"）。
 */
@RestController
@RequestMapping("/api/v1/skills/{skillId}/source")
public class SkillSourceController {

    private final GitSourceUpdateService updates;
    private final CurrentUserService currentUserService;

    public SkillSourceController(GitSourceUpdateService updates, CurrentUserService currentUserService) {
        this.updates = updates;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public SkillSourceResponse info(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable UUID skillId) {
        return updates.info(currentUserService.current(jwt), skillId);
    }

    @PostMapping("/check-updates")
    public SourceUpdateCheckResponse checkUpdates(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable UUID skillId) {
        return updates.check(currentUserService.current(jwt), skillId);
    }

    /**
     * 拉取远端新提交并产出新版本。
     *
     * 走的是和 CLI 发布一样的链路：扫描 → 个人与公开直接发布，
     * 团队与组织进待审队列。
     */
    @PostMapping("/update")
    public SourceUpdateResultResponse update(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable UUID skillId) {
        return updates.update(currentUserService.current(jwt), skillId);
    }
}
