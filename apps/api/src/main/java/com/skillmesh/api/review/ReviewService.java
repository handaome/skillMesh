package com.skillmesh.api.review;

import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.org.OrgService;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.version.SkillVersionEntity;
import com.skillmesh.api.version.SkillVersionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发布审核（设计文档 §10.1：draft → scanning → pending_review → published）。
 *
 * 个人与公开的 Skill 扫描通过即发布；团队与组织可见的版本停在 pending_review，
 * 由 reviewer 及以上角色放行——团队共享的东西不能谁都能直接发上去。
 */
@Service
public class ReviewService {

    private final SkillRepository skills;
    private final SkillVersionRepository versions;
    private final OrgService orgs;
    private final AuditService audit;
    private final ReviewRepository reviews;

    public ReviewService(SkillRepository skills, SkillVersionRepository versions,
                         OrgService orgs, AuditService audit, ReviewRepository reviews) {
        this.skills = skills;
        this.versions = versions;
        this.orgs = orgs;
        this.audit = audit;
        this.reviews = reviews;
    }

    /** 团队或组织可见的 Skill，发版要走审核。 */
    public boolean needsReview(UUID skillId) {
        SkillEntity skill = skills.findById(skillId).orElse(null);
        return skill != null
            && Set.of("team", "organization").contains(skill.getVisibility());
    }

    /** 提交审核：把待审的版本重新排队。 */
    @Transactional
    public Map<String, Object> submit(CurrentUser user, UUID skillId, String version,
                                     String comment) {
        SkillEntity skill = requireSkill(user, skillId);
        // 提交审核是「发布」的一部分，门槛与 publish 一致：判组织角色。
        // 判团队角色的话，接受邀请写下的 team_role=member 会把组织角色永久遮住——
        // 手册教的「把组织角色提到 publisher」在服务端就不生效。
        requireRole(user, skill, "publisher", null);
        SkillVersionEntity entity = requireVersion(skillId, version);
        if ("published".equals(entity.getStatus())) {
            throw new ApiException(409, ErrorCodes.VERSION_CONFLICT, "该版本已发布，无需再提交审核");
        }
        entity.setStatus("pending_review");
        versions.save(entity);
        record(user, skillId, "version.submit", entity, comment);
        return Map.of("skill_id", skillId.toString(), "version", version,
            "status", "pending_review");
    }

    /** 审核通过并发布。 */
    @Transactional
    public Map<String, Object> approve(CurrentUser user, UUID skillId, String version,
                                      String comment) {
        return decide(user, skillId, version, true, comment);
    }

    /** 审核拒绝。 */
    @Transactional
    public Map<String, Object> reject(CurrentUser user, UUID skillId, String version,
                                     String comment) {
        return decide(user, skillId, version, false, comment);
    }

    private Map<String, Object> decide(CurrentUser user, UUID skillId, String version,
                                      boolean approved, String comment) {
        SkillEntity skill = requireSkill(user, skillId);
        // 自己发的东西自己审不合适：要求 reviewer 及以上，且不能是同一个发布人
        // 审核放行权是团队范围内的事，判团队角色（没有团队成员行才回落到组织角色）。
        requireRole(user, skill, "reviewer", skill.getOwnerTeamId());
        SkillVersionEntity entity = requireVersion(skillId, version);
        if (!"pending_review".equals(entity.getStatus())) {
            throw new ApiException(409, ErrorCodes.VERSION_CONFLICT,
                "只有待审核的版本能过审，当前状态：" + entity.getStatus());
        }
        if (approved && entity.getCreatedBy() != null && entity.getCreatedBy().equals(user.id())) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "不能审核自己发布的版本");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (approved) {
            entity.setStatus("published");
            entity.setPublishedBy(user.id());
            entity.setPublishedAt(now);
            skill.setLatestVersion(entity.getVersion());
            skill.setUpdatedAt(now);
            skills.save(skill);
        } else {
            entity.setStatus("rejected");
        }
        versions.save(entity);
        record(user, skillId, approved ? "version.approve" : "version.reject", entity, comment);
        return Map.of("skill_id", skillId.toString(), "version", version,
            "status", entity.getStatus());
    }

    private void record(CurrentUser user, UUID skillId, String action,
                        SkillVersionEntity version, String comment) {
        ReviewEntity row = new ReviewEntity();
        row.setId(UUID.randomUUID());
        row.setSkillVersionId(version.getId());
        row.setReviewerId(user.id());
        row.setDecision("version.reject".equals(action) ? "rejected" : "approved");
        row.setComment(comment == null ? "" : comment);
        row.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        reviews.save(row);
        audit.record(user, action, "skill", skillId, "success",
            Map.of("version", version.getVersion(),
                "comment", comment == null ? "" : comment));
    }

    private SkillEntity requireSkill(CurrentUser user, UUID skillId) {
        SkillEntity skill = skills.findById(skillId)
            .orElseThrow(() -> ApiException.notFound("Skill"));
        if (skill.getOrganizationId() == null) {
            // 个人 Skill 没有发布者角色这一说，属主自己说了算
            if (!user.id().equals(skill.getOwnerUserId())) {
                throw ApiException.notFound("Skill");
            }
            return skill;
        }
        return skill;
    }

    private SkillVersionEntity requireVersion(UUID skillId, String version) {
        return versions.findBySkillIdAndVersion(skillId, version)
            .orElseThrow(() -> ApiException.notFound("版本"));
    }

    /**
     * 角色门槛。
     *
     * scopeTeamId 传团队 id 就按团队范围判（审核放行权，团队内的事），
     * 传 null 只判组织角色（发布门槛，与 SkillService.requireVisibilitySupported 一致）。
     * 两者别混：接受邀请会写一行 team_role=member，团队角色优先的组织角色会被它遮住。
     */
    private void requireRole(CurrentUser user, SkillEntity skill, String required, UUID scopeTeamId) {
        if (skill.getOrganizationId() == null) {
            return;
        }
        String role = orgs.roleIn(skill.getOrganizationId(), scopeTeamId, user.id());
        if (!OrgService.roleAtLeast(role, required)) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN,
                "需要 " + required + " 及以上角色，当前：" + (role.isEmpty() ? "无" : role));
        }
    }
}
