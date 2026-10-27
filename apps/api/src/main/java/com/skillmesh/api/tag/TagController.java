package com.skillmesh.api.tag;

import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.skill.SkillService;
import com.skillmesh.api.skill.dto.TagCountResponse;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 标签清单与数量。
 *
 * 单独一个端点而不是挂在 /skills 下：/skills/{skillId} 那一段是变量，
 * 挂在下面会被它抢走，报出来的是「参数格式不对」，看不出是路由撞了。
 */
@RestController
@RequestMapping("/api/v1/tags")
public class TagController {

    private final SkillService skills;
    private final CurrentUserService currentUserService;

    public TagController(SkillService skills, CurrentUserService currentUserService) {
        this.skills = skills;
        this.currentUserService = currentUserService;
    }

    /**
     * 我可见的标签与各自的数量。
     *
     * mine=true 时只统计我名下的（个人空间 + 我建的团队 Skill），
     * 与 GET /skills?mine=true 同一口径。
     */
    @GetMapping
    public List<TagCountResponse> list(@AuthenticationPrincipal Jwt jwt,
                                        @RequestParam(defaultValue = "false") boolean mine) {
        return skills.tags(currentUserService.current(jwt), mine);
    }
}
