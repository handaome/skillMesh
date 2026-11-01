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
import com.skillmesh.api.common.ContentDigest;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.org.AuthorizationService;
import com.skillmesh.api.scan.ScanRepository;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SkillService;
import com.skillmesh.api.upload.UploadSessionRepository;
import com.skillmesh.api.version.dto.CreateVersionRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 基线前置条件对不上就拒（设计文档 §4.3 API-002）。
 *
 * 客户端做决策时看到的云端摘要（expected_base_revision）必须还成立。
 * 对不上说明云端在这期间被别人动过——这次上传会把那份改动静默盖掉，
 * 所以要回 409 VERSION_CONFLICT 让客户端重新对账，而不是照建。
 */
class BaseRevisionConflictTest {

    private final SkillVersionRepository versions = mock(SkillVersionRepository.class);
    private final SkillRepository skills = mock(SkillRepository.class);
    private final SkillService skillService = mock(SkillService.class);
    private final UploadSessionRepository uploads = mock(UploadSessionRepository.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);

    private static final UUID SKILL_ID = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    /** 云端现在的已发布内容：一份清单，摘要由它算。 */
    private static final List<Map<String, Object>> CLOUD_MANIFEST = List.of(
        Map.of("path", "SKILL.md", "sha256", "a".repeat(64), "size_bytes", 12L));

    @Test
    void 云端被别人改过之后带着旧基线建版本会被挡下() {
        云端已发布版本();
        String stale = "b".repeat(64);

        assertThatThrownBy(() -> 服务().create(用户(), SKILL_ID, 请求(stale)))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> {
                ApiException api = (ApiException) ex;
                assertThat(api.status()).isEqualTo(409);
                assertThat(api.code()).isEqualTo(ErrorCodes.VERSION_CONFLICT);
            });
    }

    @Test
    void 基线还对得上就照常建版本() {
        云端已发布版本();

        var response = 服务().create(用户(), SKILL_ID, 请求(ContentDigest.of(CLOUD_MANIFEST)));

        assertThat(response).isNotNull();
    }

    /** 云端已有一个已发布版本，摘要就是 {@link #CLOUD_MANIFEST} 的摘要。 */
    private void 云端已发布版本() {
        SkillEntity skill = new SkillEntity();
        skill.setId(SKILL_ID);
        skill.setOwnerUserId(OWNER);
        when(skillService.requireVisibleSkill(any(), eq(SKILL_ID))).thenReturn(skill);
        when(skills.findByIdForUpdate(SKILL_ID)).thenReturn(Optional.of(skill));

        SkillVersionEntity published = new SkillVersionEntity();
        published.setId(UUID.randomUUID());
        published.setSkillId(SKILL_ID);
        published.setStatus(VersionStatus.published);
        published.setArtifactId(UUID.randomUUID());
        published.setManifestJson(CLOUD_MANIFEST);
        when(versions.findBySkillIdOrderByCreatedAtDescThenId(SKILL_ID))
            .thenReturn(List.of(published));
    }

    private SkillVersionService 服务() {
        return new SkillVersionService(mock(ScanRepository.class), versions, skills, skillService,
            uploads, artifacts, mock(ArtifactStorage.class), mock(AuditService.class),
            mock(AuthorizationService.class));
    }

    private static CreateVersionRequest 请求(String expectedBaseRevision) {
        return new CreateVersionRequest("1.0.0", "stable", null, null, "改动", 12L, 1,
            "c".repeat(64),
            List.of(new CreateVersionRequest.ManifestEntry(
                "SKILL.md", "a".repeat(64), 12L, false)),
            expectedBaseRevision);
    }

    private static CurrentUser 用户() {
        return new CurrentUser(OWNER, "u-" + OWNER, "探针用户", null, UserStatus.active, "user");
    }
}
