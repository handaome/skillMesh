package com.skillmesh.api.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillmesh.api.artifact.ArtifactService;
import com.skillmesh.api.artifact.ArtifactStorage;
import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.UserStatus;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.org.AuthorizationService;
import com.skillmesh.api.scan.ScanRepository;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SkillService;
import com.skillmesh.api.upload.UploadSessionRepository;
import com.skillmesh.api.version.dto.CreateVersionRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 同一个 Skill 建重名版本要被挡下（设计文档 §4.3 DB-001）。
 *
 * 服务层先查一遍版本号在不在，在就回 409；数据库上的 UNIQUE(skill_id, version)
 * 是第二道。发出去的错误码是 VERSION_CONFLICT——不是别的码。
 */
class DuplicateVersionConflictTest {

    private final SkillVersionRepository versions = mock(SkillVersionRepository.class);
    private final SkillRepository skills = mock(SkillRepository.class);
    private final SkillService skillService = mock(SkillService.class);

    private static final UUID SKILL_ID = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    @Test
    void 版本号重了就是409而不是别的码() {
        SkillEntity skill = new SkillEntity();
        skill.setId(SKILL_ID);
        skill.setOwnerUserId(OWNER);
        when(skillService.requireVisibleSkill(any(), eq(SKILL_ID))).thenReturn(skill);
        when(skills.findByIdForUpdate(SKILL_ID)).thenReturn(Optional.of(skill));
        when(versions.existsBySkillIdAndVersion(SKILL_ID, "1.0.0")).thenReturn(true);

        assertThatThrownBy(() -> 服务().create(用户(), SKILL_ID, 请求("1.0.0")))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.status()).isEqualTo(409);
                assertThat(api.code()).isEqualTo(ErrorCodes.VERSION_CONFLICT);
            });
    }

    private SkillVersionService 服务() {
        return new SkillVersionService(mock(ScanRepository.class), versions, skills, skillService,
            mock(UploadSessionRepository.class), mock(ArtifactService.class),
            mock(ArtifactStorage.class), mock(AuditService.class),
            mock(AuthorizationService.class));
    }

    private static CreateVersionRequest 请求(String version) {
        return new CreateVersionRequest(version, "stable", null, null, "改动", 12L, 1,
            "c".repeat(64),
            List.of(new CreateVersionRequest.ManifestEntry("SKILL.md", "a".repeat(64), 12L, false)),
            "");
    }

    private static CurrentUser 用户() {
        return new CurrentUser(OWNER, "u-" + OWNER, "探针用户", null, UserStatus.active, "user");
    }
}
