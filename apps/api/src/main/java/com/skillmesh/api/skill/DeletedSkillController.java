package com.skillmesh.api.skill;

import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.common.CursorPage;
import com.skillmesh.api.skill.dto.DeletedSkillResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已删除（归档）的 Skill 清单。
 *
 * 单独一个路径而不是 {@code /skills/deleted}：那一条会被
 * {@code GET /skills/{skillId}} 抢走——skillId 那一段是变量，
 * "deleted" 被当成 id 去解析 UUID，报出来的是「参数格式不对」，
 * 看不出是路由撞了。
 */
@RestController
@RequestMapping("/api/v1/deleted-skills")
public class DeletedSkillController {

    private final SkillService skills;
    private final CurrentUserService currentUserService;

    public DeletedSkillController(SkillService skills, CurrentUserService currentUserService) {
        this.skills = skills;
        this.currentUserService = currentUserService;
    }

    /** 自己删掉过哪些 Skill。 */
    @GetMapping
    public CursorPage<DeletedSkillResponse> list(@AuthenticationPrincipal Jwt jwt,
                                 @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
                                 @RequestParam(required = false) String cursor) {
        return skills.deleted(currentUserService.current(jwt), limit, cursor);
    }
}
