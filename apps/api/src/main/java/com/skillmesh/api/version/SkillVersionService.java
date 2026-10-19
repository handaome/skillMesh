package com.skillmesh.api.version;

import com.skillmesh.api.artifact.ArtifactService;
import com.skillmesh.api.artifact.ArtifactStorage;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ContentDigest;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SkillService;
import com.skillmesh.api.upload.UploadSessionEntity;
import com.skillmesh.api.upload.UploadSessionRepository;
import com.skillmesh.api.version.dto.CreateVersionRequest;
import com.skillmesh.api.version.dto.CreateVersionResponse;
import com.skillmesh.api.version.dto.CreateVersionResponse.UploadTarget;
import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ContentDigest;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.scan.ScanEntity;
import com.skillmesh.api.scan.ScanRepository;
import com.skillmesh.api.version.dto.SkillVersionResponse;
import com.skillmesh.api.version.dto.VersionPage;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkillVersionService {

    private final SkillVersionRepository versions;
    private final SkillRepository skills;
    private final SkillService skillService;
    private final UploadSessionRepository uploads;
    private final ArtifactService artifacts;
    private final ArtifactStorage storage;
    private final ScanRepository scans;
    private final AuditService audit;

    public SkillVersionService(ScanRepository scans, SkillVersionRepository versions,
                               SkillRepository skills, SkillService skillService,
                               UploadSessionRepository uploads, ArtifactService artifacts,
                               ArtifactStorage storage, AuditService audit) {
        this.versions = versions;
        this.skills = skills;
        this.skillService = skillService;
        this.scans = scans;
        this.audit = audit;
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
        version.setManifestJson(toManifestJson(req.manifest()));
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
                    artifact.getSha256(), artifact.getSizeBytes(), artifact.getContentType(),
                    artifact.getSignature(), artifact.getSignatureKeyId(),
                    version.getManifestJson() == null || version.getManifestJson().isEmpty()
                        ? "" : ContentDigest.of(version.getManifestJson()));
            }
            items.add(new SkillVersionResponse(version.getId(), version.getSkillId(),
                version.getVersion(), version.getChannel(), version.getStatus(),
                version.getSourceCommit(), summary, manifestOf(version), scanOf(version),
                version.getPublishedAt(), version.getCreatedAt()));
        }
        return new VersionPage(items, null, false);
    }

    /**
     * 逐文件清单随版本一起存（skill_versions.manifest_json）。
     * 客户端靠它判断内容是否真的变了——只比 zip 摘要，会把「文件权限位不同、
     * 内容一样」误判成变更（设计文档 §6.2 的规范化摘要）。
     */
    private List<SkillVersionResponse.ManifestEntry> manifestOf(SkillVersionEntity version) {
        List<SkillVersionResponse.ManifestEntry> entries = new ArrayList<>();
        for (Map<String, Object> item : version.getManifestJson()) {
            entries.add(new SkillVersionResponse.ManifestEntry(
                String.valueOf(item.get("path")),
                String.valueOf(item.get("sha256")),
                ((Number) item.getOrDefault("size_bytes", 0)).longValue()));
        }
        return entries;
    }

    /** 单个版本详情（契约里 GET /skills/{id}/versions/{version}）。 */
    @Transactional(readOnly = true)
    public SkillVersionResponse detail(CurrentUser user, UUID skillId, String version) {
        list(user, skillId); // 顺带做可见性校验：看不见就 404
        SkillVersionEntity entity = versions.findBySkillIdAndVersion(skillId, version)
            .orElseThrow(() -> ApiException.notFound("版本"));
        SkillVersionResponse.ArtifactSummary summary = null;
        if (entity.getArtifactId() != null) {
            var artifact = artifacts.require(entity.getArtifactId());
            summary = new SkillVersionResponse.ArtifactSummary(artifact.getId(),
                artifact.getSha256(), artifact.getSizeBytes(), artifact.getContentType(),
                artifact.getSignature(), artifact.getSignatureKeyId(),
                entity.getManifestJson() == null || entity.getManifestJson().isEmpty()
                    ? "" : ContentDigest.of(entity.getManifestJson()));
        }
        return new SkillVersionResponse(entity.getId(), entity.getSkillId(), entity.getVersion(),
            entity.getChannel(), entity.getStatus(), entity.getSourceCommit(), summary,
            manifestOf(entity), scanOf(entity), entity.getPublishedAt(), entity.getCreatedAt());
    }

    /** 最近一次扫描结论，没有就返回 null——没扫过和扫过是两回事，不能混。 */
    private SkillVersionResponse.ScanSummary scanOf(SkillVersionEntity version) {
        return scans.findFirstBySkillVersionIdOrderByStartedAtDesc(version.getId())
            .map(ScanEntity::toSummary)
            .orElse(null);
    }

    /** 请求里的 manifest 是 record，转成能存进 jsonb 的 Map 列表。 */
    private List<Map<String, Object>> toManifestJson(List<CreateVersionRequest.ManifestEntry> manifest) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (CreateVersionRequest.ManifestEntry item : manifest) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", item.path());
            entry.put("sha256", item.sha256());
            entry.put("size_bytes", item.size_bytes());
            entries.add(entry);
        }
        return entries;
    }

    /**
     * 撤回一个已发布版本（设计文档 §10）。
     *
     * 只允许属主撤回自己的 Skill——没有组织角色体系之前，权限就这一条。
     * 撤回不可逆：要恢复得发新版本。
     */
    @Transactional
    public Map<String, Object> revoke(CurrentUser user, UUID skillId, String version, String reason) {
        SkillEntity skill = requireVisibleSkill(user, skillId);
        if (!skill.getOwnerUserId().equals(user.id())) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "只有属主能撤回自己的版本");
        }
        SkillVersionEntity entity = versions.findBySkillIdAndVersion(skillId, version)
            .orElseThrow(() -> ApiException.notFound("版本"));
        if ("revoked".equals(entity.getStatus())) {
            throw new ApiException(409, ErrorCodes.VERSION_CONFLICT, "该版本已经是撤回状态");
        }
        String previous = entity.getStatus();
        // DDL 要求 revoked 必须带 revoked_at（V1__init.sql 的 check 约束）。
        entity.setStatus("revoked");
        entity.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setRevokeReason(reason == null ? "" : reason);
        versions.save(entity);

        audit.record(user, "version.revoke", "skill", skillId, "success",
            Map.of("version", version, "reason", reason == null ? "" : reason,
                "previous_status", previous));

        return Map.of("skill_id", skillId.toString(), "version", version,
            "status", "revoked", "reason", reason == null ? "" : reason);
    }

    SkillEntity requireVisibleSkill(CurrentUser user, UUID skillId) {
        SkillEntity skill = skills.findById(skillId).orElseThrow(() -> ApiException.notFound("Skill"));
        if (!skillService.canView(user, skill)) {
            throw ApiException.notFound("Skill");
        }
        return skill;
    }
}
