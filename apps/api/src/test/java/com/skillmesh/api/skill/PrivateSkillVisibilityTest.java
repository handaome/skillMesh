package com.skillmesh.api.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.UserStatus;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.common.Visibility;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 私有 Skill 对外人不可见（设计文档 §4.3 AUTH-001）。
 *
 * 看不见的东西要回 404 而不是 403：回 403 等于告诉外人「它存在，只是你不能看」，
 * 那本身就是个信息泄露。属主本人当然看得见。
 */
class PrivateSkillVisibilityTest {

    private final SkillRepository skills = mock(SkillRepository.class);
    private final com.skillmesh.api.org.OrgService orgs =
        mock(com.skillmesh.api.org.OrgService.class);

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    @Test
    void 别人看私有Skill得到的是404不是403() {
        SkillEntity skill = privateSkill();
        when(skills.findById(skill.getId())).thenReturn(Optional.of(skill));

        assertThatThrownBy(() -> service().requireVisibleSkill(user(STRANGER), skill.getId()))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.status()).isEqualTo(404);
                assertThat(api.code()).isEqualTo(ErrorCodes.NOT_FOUND);
            });
    }

    @Test
    void 属主看得见自己的私有Skill() {
        SkillEntity skill = privateSkill();
        when(skills.findById(skill.getId())).thenReturn(Optional.of(skill));

        assertThat(service().requireVisibleSkill(user(OWNER), skill.getId())).isSameAs(skill);
    }

    private com.skillmesh.api.skill.SkillService service() {
        return new com.skillmesh.api.skill.SkillService(skills, orgs,
            mock(com.skillmesh.api.skill.SourceRepository.class),
            mock(com.skillmesh.api.audit.AuditService.class),
            mock(com.skillmesh.api.org.AuthorizationService.class),
            mock(org.springframework.transaction.PlatformTransactionManager.class));
    }

    private static SkillEntity privateSkill() {
        SkillEntity skill = new SkillEntity();
        skill.setId(UUID.randomUUID());
        skill.setSlug("private-probe");
        skill.setName("探针");
        skill.setOwnerUserId(OWNER);
        skill.setVisibility(Visibility.PRIVATE);
        return skill;
    }

    private static CurrentUser user(UUID id) {
        return new CurrentUser(id, "u-" + id, "探针用户", null, UserStatus.active, "user");
    }
}
