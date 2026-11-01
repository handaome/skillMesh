package com.skillmesh.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.UserStatus;
import com.skillmesh.api.org.AuthorizationService;
import com.skillmesh.api.org.Role;
import com.skillmesh.api.review.ReviewRepository;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 谁能翻一个 Skill 的审计流水（§2.4 权限矩阵「查看组织审计」一行）。
 *
 * 看得见这个 Skill 不等于能看它的流水：团队成员能看见团队 Skill，
 * 但流水里有撤回记录、发布人这些该给属主与管理员看的东西。
 */
class AuditPermissionTest {

    private final AuditRepository repository = mock(AuditRepository.class);
    private final SkillRepository skills = mock(SkillRepository.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final AuditService service =
        new AuditService(repository, skills, authorization, reviews);

    private static final UUID USER = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID SKILL = UUID.randomUUID();

    @Test
    void 属主能看自己Skill的流水() {
        assertThat(service.canSeeAudit(user(), skill(SKILL, USER, ORG))).isTrue();
    }

    @Test
    void 组织管理员能看() {
        when(authorization.orgRole(USER, ORG)).thenReturn(Role.admin);
        assertThat(service.canSeeAudit(user(), skill(SKILL, UUID.randomUUID(), ORG))).isTrue();
    }

    @Test
    void 审过的人能看() {
        when(authorization.orgRole(USER, ORG)).thenReturn(Role.member);
        when(reviews.existsByReviewerIdAndSkillId(USER, SKILL)).thenReturn(true);
        assertThat(service.canSeeAudit(user(), skill(SKILL, UUID.randomUUID(), ORG))).isTrue();
    }

    @Test
    void 团队成员看不到() {
        when(authorization.orgRole(USER, ORG)).thenReturn(Role.member);
        when(reviews.existsByReviewerIdAndSkillId(USER, SKILL)).thenReturn(false);
        assertThat(service.canSeeAudit(user(), skill(SKILL, UUID.randomUUID(), ORG))).isFalse();
    }

    @Test
    void 个人Skill只有属主能看() {
        when(reviews.existsByReviewerIdAndSkillId(USER, SKILL)).thenReturn(false);
        assertThat(service.canSeeAudit(user(), skill(SKILL, UUID.randomUUID(), null))).isFalse();
    }

    private static CurrentUser user() {
        return new CurrentUser(USER, "someone", "someone", "someone@example.com",
            UserStatus.active, "user");
    }

    private static SkillEntity skill(UUID id, UUID ownerId, UUID organizationId) {
        SkillEntity skill = new SkillEntity();
        skill.setId(id);
        skill.setOwnerUserId(ownerId);
        skill.setOrganizationId(organizationId);
        return skill;
    }
}
