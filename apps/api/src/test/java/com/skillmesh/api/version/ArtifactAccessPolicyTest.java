package com.skillmesh.api.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillmesh.api.artifact.ArtifactEntity;
import com.skillmesh.api.artifact.ArtifactRepository;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.UserStatus;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.common.Visibility;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SkillService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 撤回与隔离的版本，制品不该再被取走（设计文档 §10.1）。
 *
 * 这是跨安全边界的一条：能看见 Skill 的人拿到 artifact id 直接下载，
 * 就能拿到已撤回（紧急事件撤回）或已隔离（内容里有真凭据）的内容。
 */
class ArtifactAccessPolicyTest {

    private final SkillVersionRepository versions = mock(SkillVersionRepository.class);
    private final SkillRepository skills = mock(SkillRepository.class);
    private final ArtifactRepository artifacts = mock(ArtifactRepository.class);
    private final SkillService skillService = mock(SkillService.class);

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    @Test
    void 已撤回的版本别人下不到属主下得到() {
        UUID artifactId = UUID.randomUUID();
        摆好一个版本(artifactId, VersionStatus.revoked);

        assertThatThrownBy(() -> policy().requireDownloadable(user(STRANGER), artifactId))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(404));

        assertThatCode(() -> policy().requireDownloadable(user(OWNER), artifactId))
            .doesNotThrowAnyException();
    }

    @Test
    void 已隔离的版本谁都下不到() {
        UUID artifactId = UUID.randomUUID();
        摆好一个版本(artifactId, VersionStatus.quarantined);

        assertThatThrownBy(() -> policy().requireDownloadable(user(OWNER), artifactId))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(ErrorCodes.NOT_FOUND));
    }

    @Test
    void 已发布的版本正常能下() {
        UUID artifactId = UUID.randomUUID();
        摆好一个版本(artifactId, VersionStatus.published);

        assertThatCode(() -> policy().requireDownloadable(user(STRANGER), artifactId))
            .doesNotThrowAnyException();
    }

    @Test
    void 同一份内容被多个版本引用时带出放行的那一版() {
        UUID artifactId = UUID.randomUUID();
        // 撤回的那一版排在前面：按「第一个引用它的版本」记账就会记错。
        SkillVersionEntity revoked = 造一个版本(artifactId, VersionStatus.revoked);
        SkillVersionEntity published = 造一个版本(artifactId, VersionStatus.published);
        when(versions.findByArtifactId(artifactId)).thenReturn(List.of(revoked, published));

        ArtifactAccessPolicy.DownloadGrant grant = policy().requireDownloadable(user(STRANGER), artifactId);

        assertThat(grant.version().getId()).isEqualTo(published.getId());
        assertThat(grant.artifact().getId()).isEqualTo(artifactId);
    }

    /** 摆一份制品与一个引用它的版本。 */
    private void 摆好一个版本(UUID artifactId, VersionStatus status) {
        SkillVersionEntity version = 造一个版本(artifactId, status);
        when(versions.findByArtifactId(artifactId)).thenReturn(List.of(version));
    }

    /** 造一个引用这份制品的版本，并把它所属的 Skill 摆好。 */
    private SkillVersionEntity 造一个版本(UUID artifactId, VersionStatus status) {
        ArtifactEntity artifact = new ArtifactEntity();
        artifact.setId(artifactId);
        when(artifacts.findById(artifactId)).thenReturn(Optional.of(artifact));

        SkillVersionEntity version = new SkillVersionEntity();
        version.setId(UUID.randomUUID());
        version.setSkillId(UUID.randomUUID());
        version.setVersion("1.0.0");
        version.setStatus(status);
        version.setArtifactId(artifactId);

        SkillEntity skill = new SkillEntity();
        skill.setId(version.getSkillId());
        skill.setSlug("probe");
        skill.setOwnerUserId(OWNER);
        skill.setVisibility(Visibility.PUBLIC);
        when(skills.findById(version.getSkillId())).thenReturn(Optional.of(skill));
        when(skillService.canView(any(), any())).thenReturn(true);
        return version;
    }

    private ArtifactAccessPolicy policy() {
        return new ArtifactAccessPolicy(versions, skills, artifacts, skillService);
    }

    private static CurrentUser user(UUID id) {
        return new CurrentUser(id, "u-" + id, "探针用户", null, UserStatus.active, "user");
    }
}
