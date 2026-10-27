package com.skillmesh.api.audit;

import com.skillmesh.api.audit.dto.ReportAuditEventRequest;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.install.InstallationRepository;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SkillService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户端上报的审计事件（详细设计 §12）。
 *
 * 只接受少量客户端自己知道的动作：特权事件（发布、撤回、权限变更）由服务端自己记，
 * 客户端报上来的一律忽略，免得出现两套互相矛盾的流水。
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditReportController {

    /** 允许客户端上报的动作。别的 action 直接 400。 */
    private static final Set<String> ALLOWED = Set.of(
        "install.completed", "install.failed", "install.upgraded", "install.rolled_back",
        "uninstall.completed", "sync.conflict_reported", "sync.retried",
        // 同步的三种结果：内容往上传了、从云端拉回来了、云端删了所以本地也删了。
        // 服务端只知道前两者的结果（版本记录里就有），第三种只有客户端看得见。
        "sync.uploaded", "sync.downloaded", "sync.local_deleted");

    private final AuditService audit;
    private final CurrentUserService currentUserService;
    private final SkillService skills;
    private final SkillRepository skillRepository;
    private final InstallationRepository installations;

    public AuditReportController(AuditService audit, CurrentUserService currentUserService,
            SkillService skills, SkillRepository skillRepository,
            InstallationRepository installations) {
        this.audit = audit;
        this.currentUserService = currentUserService;
        this.skills = skills;
        this.skillRepository = skillRepository;
        this.installations = installations;
    }

    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> report(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ReportAuditEventRequest req) {
        if (!ALLOWED.contains(req.action())) {
            throw new com.skillmesh.api.common.ApiException(400,
                com.skillmesh.api.common.ErrorCodes.VALIDATION_FAILED,
                "客户端不能上报这个动作：" + req.action() + "（允许：" + String.join(", ", ALLOWED) + "）");
        }
        var user = currentUserService.current(jwt);
        String targetType = req.target_type() == null ? "skill" : req.target_type();
        String targetId = req.target_id();
        // target 是客户端自报的：得先确认上报者跟这个 Skill 真有关系，
        // 否则任何登录用户都能往别人的 Skill 流水里塞「安装完成」这类条目，
        // 审计台分不出真假。同仓库别的写路径（改 Skill、看制品）都要过可见性。
        if ("skill".equals(targetType)) {
            requireRelatedSkill(user, targetId);
        }
        audit.record(user, req.action(), targetType, targetId,
            req.outcome() == null ? "success" : req.outcome(), req.details());
        return ResponseEntity.created(URI.create("/api/v1/audit/events"))
            .body(Map.of("accepted", true, "action", req.action()));
    }

    /**
     * 上报者得跟这个 Skill 有关系，才让他往它的流水里写东西。
     *
     * 只判「看得见」太松：公开 Skill 谁都看得见，于是任何登录用户都能往
     * 别人的 Skill 流水里塞「安装完成」这类条目，审计台就分不出真假。
     * 这里要的是三种关系之一：它是属主、它是这个组织/团队的成员、
     * 或者它真装过这个 Skill（安装记录摆在那儿）。
     *
     * 「有关系」的判定在 {@link SkillService#isRelated}：
     * 可见性与关系是两回事，判定口径放在一处，改权限时只改一个地方。
     */
    private void requireRelatedSkill(CurrentUser user, String targetId) {
        if (targetId == null) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "缺少 target_id");
        }
        UUID skillId;
        try {
            skillId = UUID.fromString(targetId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "target_id 不是合法的 Skill id");
        }
        var skill = skillRepository.findById(skillId).orElse(null);
        if (skill == null || !skills.canView(user, skill)) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "无权给这个 Skill 上报审计事件");
        }
        if (skills.isRelated(user, skill)) {
            return;
        }
        if (installations.existsByUserIdAndSkillId(user.id(), skillId)) {
            return;
        }
        throw new ApiException(403, ErrorCodes.FORBIDDEN,
            "你没装过这个 Skill，也不是它的属主或成员，不能给它上报审计事件");
    }
}
