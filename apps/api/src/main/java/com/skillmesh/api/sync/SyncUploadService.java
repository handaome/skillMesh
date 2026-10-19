package com.skillmesh.api.sync;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillmesh.api.artifact.ArtifactEntity;
import com.skillmesh.api.artifact.ArtifactService;
import com.skillmesh.api.artifact.ArtifactStorage;
import com.skillmesh.api.artifact.SigningService;
import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ContentDigest;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.device.DeviceService;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.upload.UploadSessionEntity;
import com.skillmesh.api.upload.UploadSessionRepository;
import com.skillmesh.api.version.SkillVersionEntity;
import com.skillmesh.api.version.SkillVersionRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件级同步上传与提交（详细设计 §2.4.2、§12）。
 *
 * 和整包上传是两条路：这里一个文件一个会话，全部传完后 commit，
 * 服务端按云端基线判断能不能提交——不能就转冲突，双方内容都留着。
 */
@Service
public class SyncUploadService {

    private static final int MAX_FILES = 5000;
    private static final long MAX_TOTAL_BYTES = 50L << 20;

    private final SyncChangesetRepository changesets;
    private final UploadSessionRepository uploads;
    private final SkillRepository skills;
    private final SkillVersionRepository versions;
    private final ArtifactService artifacts;
    private final ArtifactStorage storage;
    private final DeviceService devices;
    private final AuditService audit;
    private final SigningService signing;

    public SyncUploadService(SyncChangesetRepository changesets,
                             UploadSessionRepository uploads, SkillRepository skills,
                             SkillVersionRepository versions, ArtifactService artifacts,
                             ArtifactStorage storage, DeviceService devices,
                             AuditService audit, SigningService signing) {
        this.changesets = changesets;
        this.uploads = uploads;
        this.skills = skills;
        this.versions = versions;
        this.artifacts = artifacts;
        this.storage = storage;
        this.devices = devices;
        this.audit = audit;
        this.signing = signing;
    }

    /** 为变更集里的每个相对路径开一个上传会话。 */
    @Transactional
    public List<UploadSlot> openUploads(CurrentUser user, UUID changesetId, List<FileSpec> files) {
        SyncChangesetEntity changeset = requireChangeset(user, changesetId);
        if (files.size() > MAX_FILES) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "一次最多上传 " + MAX_FILES + " 个文件");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<UploadSlot> slots = new ArrayList<>();
        for (FileSpec file : files) {
            requireSafePath(file.path());
            UploadSessionEntity session = new UploadSessionEntity();
            session.setId(UUID.randomUUID());
            session.setActorUserId(user.id());
            session.setDeviceId(changeset.getDeviceId());
            session.setPurpose("sync_file");
            session.setChangesetId(changesetId);
            session.setRelativePath(file.path());
            session.setTemporaryStorageKey(tempKey(changesetId, file.path()));
            session.setExpectedSha256(file.sha256());
            session.setExpectedSizeBytes(file.sizeBytes());
            session.setStatus("created");
            session.setCreatedAt(now);
            session.setExpiresAt(now.plus(ArtifactService.UPLOAD_TTL));
            uploads.save(session);

            slots.add(new UploadSlot(file.path(), session.getId().toString(),
                storage.presignedPut(session.getTemporaryStorageKey(),
                    ArtifactService.UPLOAD_TTL),
                Map.of("content-type", file.contentType() == null ? "application/octet-stream"
                    : file.contentType())));
        }
        changeset.setStatus("processing");
        changeset.setStartedAt(now);
        // 建变更集时就记了完成时间，现在开始处理了得清掉：
        // DDL 要求 completed_at >= started_at，留着会被约束挡下。
        changeset.setCompletedAt(null);
        changesets.save(changeset);
        return slots;
    }

    /**
     * 提交变更集：云端基线还对得上就生成新版本，对不上就转冲突。
     * 期望的云端 revision 用 base_revision 表达——那是上次同步时记下的内容摘要。
     */
    @Transactional
    public Map<String, Object> commit(CurrentUser user, UUID changesetId,
                                     String expectedRevision) throws IOException {
        SyncChangesetEntity changeset = requireChangeset(user, changesetId);
        String cloud = cloudRevision(changeset.getSkillId());
        if (expectedRevision != null && !expectedRevision.isBlank()
            && !expectedRevision.equals(cloud)) {
            changeset.setStatus("conflict");
            changesets.save(changeset);
            audit.record(user, "sync.commit_conflict", "skill", changeset.getSkillId(),
                "denied", Map.of("expected", expectedRevision, "cloud", orEmpty(cloud)));
            throw new ApiException(409, ErrorCodes.VERSION_CONFLICT,
                "云端已经变了（期望 " + shortDigest(expectedRevision) + "，实际 "
                    + shortDigest(cloud) + "），本次提交没生效");
        }

        List<UploadedFile> files = collectUploadedFiles(changesetId);
        byte[] zip = zip(files);
        String sha256 = ArtifactService.sha256(zip);
        String finalKey = "sync/" + changesetId + "/" + sha256 + ".zip";
        storage.put(finalKey, zip, "application/zip");

        ArtifactEntity artifact = artifacts.record(sha256, finalKey, zip.length, files.size(),
            manifestJson(files));
        String signature = signing.sign(ContentDigest.of(manifestJson(files)));
        if (!signature.isEmpty()) {
            artifact.setSignature(signature);
            artifact.setSignatureKeyId(signing.keyId());
            artifacts.saveArtifact(artifact);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SkillEntity skill = skills.findById(changeset.getSkillId())
            .orElseThrow(() -> ApiException.notFound("Skill"));
        SkillVersionEntity version = new SkillVersionEntity();
        version.setId(UUID.randomUUID());
        version.setSkillId(skill.getId());
        version.setVersion(nextVersion(skill.getId()));
        version.setChannel("stable");
        version.setChangelog("同步提交 " + changesetId);
        version.setManifestJson(manifestJson(files));
        version.setArtifactId(artifact.getId());
        version.setStatus("published");
        version.setCreatedBy(user.id());
        version.setCreatedAt(now);
        version.setPublishedBy(user.id());
        version.setPublishedAt(now);
        versions.save(version);
        skill.setLatestVersion(version.getVersion());
        skill.setUpdatedAt(now);
        skills.save(skill);

        changeset.setStatus("completed");
        changeset.setTargetRevision(ContentDigest.of(manifestJson(files)));
        changeset.setCompletedAt(now);
        changesets.save(changeset);

        audit.record(user, "sync.commit", "skill", skill.getId(), "success",
            Map.of("version", version.getVersion(), "files", String.valueOf(files.size())));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("changeset_id", changesetId.toString());
        result.put("status", "completed");
        result.put("version", version.getVersion());
        result.put("sha256", sha256);
        result.put("files", files.size());
        return result;
    }

    /** sync_file 会话完成时只做校验，不转正成制品——要等 commit。 */
    public boolean isSyncFile(UUID sessionId) {
        return uploads.findById(sessionId)
            .map(session -> "sync_file".equals(session.getPurpose()))
            .orElse(false);
    }

    String tempKey(UUID changesetId, String relativePath) {
        return "sync-staging/" + changesetId + "/" + relativePath;
    }

    private SyncChangesetEntity requireChangeset(CurrentUser user, UUID changesetId) {
        SyncChangesetEntity changeset = changesets.findById(changesetId)
            .orElseThrow(() -> ApiException.notFound("变更集"));
        devices.require(user, changeset.getDeviceId());
        return changeset;
    }

    private List<UploadedFile> collectUploadedFiles(UUID changesetId) throws IOException {
        List<UploadedFile> files = new ArrayList<>();
        long total = 0;
        for (UploadSessionEntity session : uploads.findByChangesetIdOrderByRelativePathAsc(changesetId)) {
            if (!"completed".equals(session.getStatus())) {
                throw new ApiException(409, ErrorCodes.VERSION_CONFLICT,
                    "还有文件没传完：" + session.getRelativePath());
            }
            byte[] data = storage.get(session.getTemporaryStorageKey()).readAllBytes();
            total += data.length;
            if (total > MAX_TOTAL_BYTES) {
                throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                    "同步内容总量超过 " + MAX_TOTAL_BYTES + " 字节");
            }
            files.add(new UploadedFile(session.getRelativePath(), data));
        }
        if (files.isEmpty()) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "变更集里没有文件");
        }
        return files;
    }

    /** 把上传的文件重新打成一个包——制品模型始终是 zip，装的时候不用认两种格式。 */
    private byte[] zip(List<UploadedFile> files) throws IOException {
        files.sort((left, right) -> left.path().compareTo(right.path()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out, java.nio.charset.StandardCharsets.UTF_8)) {
            for (UploadedFile file : files) {
                ZipEntry entry = new ZipEntry(file.path());
                // 固定时间戳：同样的内容打出同样的字节，云端才认得出「没变」
                entry.setTime(0L);
                zos.putNextEntry(entry);
                zos.write(file.data());
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private List<Map<String, Object>> manifestJson(List<UploadedFile> files) {
        List<Map<String, Object>> manifest = new ArrayList<>();
        for (UploadedFile file : files) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", file.path());
            item.put("sha256", ArtifactService.sha256(file.data()));
            item.put("size_bytes", file.data().length);
            manifest.add(item);
        }
        manifest.sort((left, right) ->
            String.valueOf(left.get("path")).compareTo(String.valueOf(right.get("path"))));
        return manifest;
    }

    /** 云端当前 revision：最新已发布版本的内容摘要。 */
    private String cloudRevision(UUID skillId) {
        for (SkillVersionEntity version : versions.findBySkillIdOrderByCreatedAtDesc(skillId)) {
            if ("published".equals(version.getStatus())
                && version.getManifestJson() != null && !version.getManifestJson().isEmpty()) {
                return ContentDigest.of(version.getManifestJson());
            }
        }
        return "";
    }

    private String nextVersion(UUID skillId) {
        String latest = skills.findById(skillId).map(SkillEntity::getLatestVersion)
            .orElse(null);
        if (latest == null || latest.isBlank()) {
            return "1.0.0";
        }
        String[] parts = latest.split("\\.");
        if (parts.length == 3) {
            try {
                return parts[0] + "." + parts[1] + "." + (Long.parseLong(parts[2]) + 1);
            } catch (NumberFormatException ignored) {
                // 非语义化版本不自己加数，交给 --version 显式指定
            }
        }
        return latest + "-sync";
    }

    /** 同步路径不能逃出变更集目录。 */
    private void requireSafePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("..")) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "相对路径不合法：" + path);
        }
    }

    private String shortDigest(String digest) {
        if (digest == null || digest.isEmpty()) {
            return "空";
        }
        return digest.length() > 8 ? digest.substring(0, 8) : digest;
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }

    public record FileSpec(String path, String sha256,
                           @JsonProperty("size_bytes") long sizeBytes,
                           @JsonProperty("content_type") String contentType) {}

    public record UploadSlot(String path,
                             @JsonProperty("upload_id") String uploadId,
                             @JsonProperty("upload_url") String uploadUrl,
                             Map<String, String> headers) {}

    record UploadedFile(String path, byte[] data) {}
}
