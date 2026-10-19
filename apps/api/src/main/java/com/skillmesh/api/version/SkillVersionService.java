package com.skillmesh.api.version;

import com.skillmesh.api.artifact.ArtifactService;
import com.skillmesh.api.artifact.ArtifactStorage;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SkillService;
import com.skillmesh.api.upload.UploadSessionEntity;
import com.skillmesh.api.upload.UploadSessionRepository;
import com.skillmesh.api.version.dto.CreateVersionRequest;
import com.skillmesh.api.version.dto.CreateVersionResponse;
import com.skillmesh.api.version.dto.CreateVersionResponse.UploadTarget;
import com.skillmesh.api.version.dto.SkillVersionResponse;
import com.skillmesh.api.version.dto.VersionPage;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkillVersionService {

    private final SkillVersionRepository versions;
    private final SkillRepository skills;
    private final UploadSessionRepository uploads;
    private final ArtifactService artifacts;
    private final ArtifactStorage storage;

    public SkillVersionService(SkillVersionRepository versions, SkillRepository skills,
                               UploadSessionRepository uploads, ArtifactService artifacts,
                               ArtifactStorage storage) {
        this.versions = versions;
        this.skills = skills;
        this.uploads = uploads;
        this.artifacts = artifacts;
        this.storage = storage;
    }

    /** 创建候选版本，同时开一个上传会话，返回直传对象存储的签名地址。 */
    @Transactional
    public CreateVersionResponse create(CurrentUser user, UUID skillId,
                                        CreateVersionRequest req) {
        requireVisibleSkill(user, skillId);
        if (versions.existsBySkillIdAndVersion(skillId, req.version())) {
            throw ApiException.versionConflict("版本已存在且不可覆盖: " + req.version());
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        SkillVersionEntity version = new SkillVersionEntity();
        version.setId(UUID.randomUUID());
        version.setSkillId(skillId);
        version.setSourceId(req.source_id());
        version.setVersion(req.version());
        version.setChannel(req.channel());
        version.setSourceCommit(req.source_commit());
        version.setChangelog(req.changelog() == null ? "" : req.changelog());
        version.setManifestJson(Map.of());
        version.setStatus("draft");
        version.setCreatedBy(user.id());
        version.setCreatedAt(now);
        versions.save(version);

        String tempKey = artifacts.tempKey(skillId, version.getId());
        UploadSessionEntity session = new UploadSessionEntity();
        session.setId(UUID.randomUUID());
        session.setActorUserId(user.id());
        session.setPurpose("skill_artifact");
        session.setSkillVersionId(version.getId());
        session.setExpectedSha256(req.sha256());
        session.setExpectedSizeBytes(req.size_bytes());
        session.setTemporaryStorageKey(tempKey);
        session.setStatus("created");
        session.setExpiresAt(now.plus(ArtifactService.UPLOAD_TTL));
        session.setCreatedAt(now);
        uploads.save(session);

        UploadTarget upload = new UploadTarget(
            session.getId(), "PUT",
            storage.presignedPut(tempKey, ArtifactService.UPLOAD_TTL),
            Map.of("Content-Type", "application/zip"),
            session.getExpiresAt());
        return new CreateVersionResponse(version.getId(), version.getStatus(), upload, null);
    }

    @Transactional(readOnly = true)
    public VersionPage list(CurrentUser user, UUID skillId) {
        requireVisibleSkill(user, skillId);
        List<SkillVersionResponse> items = new ArrayList<>();
        for (SkillVersionEntity version : versions.findBySkillIdOrderByCreatedAtDesc(skillId)) {
            SkillVersionResponse.ArtifactSummary summary = null;
            if (version.getArtifactId() != null) {
                var artifact = artifacts.require(version.getArtifactId());
                summary = new SkillVersionResponse.ArtifactSummary(artifact.getId(),
                    artifact.getSha256(), artifact.getSizeBytes(), artifact.getContentType());
            }
            items.add(new SkillVersionResponse(version.getId(), version.getSkillId(),
                version.getVersion(), version.getChannel(), version.getStatus(),
                version.getSourceCommit(), summary, version.getPublishedAt(),
                version.getCreatedAt()));
        }
        return new VersionPage(items, null, false);
    }

    SkillEntity requireVisibleSkill(CurrentUser user, UUID skillId) {
        SkillEntity skill = skills.findById(skillId).orElseThrow(() -> ApiException.notFound("Skill"));
        if (!SkillService.canView(user, skill)) {
            throw ApiException.notFound("Skill");
        }
        return skill;
    }
}
