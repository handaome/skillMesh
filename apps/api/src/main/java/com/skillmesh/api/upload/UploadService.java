package com.skillmesh.api.upload;

import com.skillmesh.api.artifact.ArtifactEntity;
import com.skillmesh.api.artifact.ArtifactService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.version.SkillVersionEntity;
import com.skillmesh.api.version.SkillVersionRepository;
import com.skillmesh.api.version.dto.UploadSession;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 上传会话收尾：服务端重算摘要、校验、写入正式对象、把版本置为已发布。
 * 摘要不匹配就不发布，并把会话标记为 failed。
 */
@Service
public class UploadService {

    private final UploadSessionRepository uploads;
    private final SkillVersionRepository versions;
    private final SkillRepository skills;
    private final ArtifactService artifacts;

    public UploadService(UploadSessionRepository uploads, SkillVersionRepository versions,
                         SkillRepository skills, ArtifactService artifacts) {
        this.uploads = uploads;
        this.versions = versions;
        this.skills = skills;
        this.artifacts = artifacts;
    }

    @Transactional
    public UploadSession complete(CurrentUser user, UUID uploadId) {
        UploadSessionEntity session = uploads.findById(uploadId)
            .orElseThrow(() -> ApiException.notFound("上传会话"));
        if (!session.getActorUserId().equals(user.id())) {
            throw ApiException.notFound("上传会话");
        }
        if (!"created".equals(session.getStatus()) && !"uploaded".equals(session.getStatus())) {
            throw new ApiException(409, ErrorCodes.VERSION_CONFLICT,
                "上传会话已结束，状态为 " + session.getStatus());
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (session.getExpiresAt().isBefore(now)) {
            session.setStatus("expired");
            session.setErrorCode("UPLOAD_EXPIRED");
            uploads.save(session);
            throw new ApiException(410, ErrorCodes.ARTIFACT_MISSING, "上传会话已过期，请重新上传");
        }

        SkillVersionEntity version = versions.findById(session.getSkillVersionId())
            .orElseThrow(() -> ApiException.notFound("版本"));
        session.setStatus("verifying");
        uploads.save(session);

        String finalKey = artifacts.finalKey(version.getSkillId(), version.getVersion(),
            session.getExpectedSha256());
        ArtifactService.Promoted promoted;
        try {
            promoted = artifacts.promote(session.getTemporaryStorageKey(), finalKey,
                session.getExpectedSha256(), session.getExpectedSizeBytes());
        } catch (ApiException e) {
            session.setStatus("failed");
            session.setErrorCode(e.code());
            session.setVerifiedAt(now);
            uploads.save(session);
            throw e;
        }

        ArtifactEntity artifact = artifacts.record(promoted.sha256(), finalKey,
            promoted.sizeBytes(), 1, List.of());

        version.setArtifactId(artifact.getId());
        version.setStatus("published");
        version.setPublishedBy(user.id());
        version.setPublishedAt(now);
        versions.save(version);

        skills.findById(version.getSkillId()).ifPresent(skill -> {
            skill.setLatestVersion(version.getVersion());
            skill.setUpdatedAt(now);
            skills.save(skill);
        });

        session.setStatus("completed");
        session.setVerifiedAt(now);
        session.setUploadedAt(now);
        uploads.save(session);

        return new UploadSession(session.getId(), session.getPurpose(), "completed", null,
            session.getExpiresAt(), promoted.sha256(), null);
    }
}
