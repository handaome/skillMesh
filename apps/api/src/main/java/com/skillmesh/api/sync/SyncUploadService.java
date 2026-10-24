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
import com.skillmesh.api.review.ReviewService;
import com.skillmesh.api.scan.ArtifactScanService;
import com.skillmesh.api.scan.ScanResult;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SkillService;
import com.skillmesh.api.upload.UploadSessionEntity;
import com.skillmesh.api.upload.UploadSessionRepository;
import com.skillmesh.api.version.SkillVersionEntity;
import com.skillmesh.api.version.SkillVersionRepository;
import com.skillmesh.api.version.VersionOrder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final SkillService skillService;
    private final SkillVersionRepository versions;
    private final ArtifactService artifacts;
    private final ArtifactStorage storage;
    private final DeviceService devices;
    private final AuditService audit;
    private final SigningService signing;
    private final ArtifactScanService scanService;
    private final ReviewService review;

    public SyncUploadService(SyncChangesetRepository changesets,
                             UploadSessionRepository uploads, SkillRepository skills,
                             SkillService skillService, SkillVersionRepository versions,
                             ArtifactService artifacts,
                             ArtifactStorage storage, DeviceService devices,
                             AuditService audit, SigningService signing,
                             ArtifactScanService scanService, ReviewService review) {
        this.changesets = changesets;
        this.uploads = uploads;
        this.skills = skills;
        this.skillService = skillService;
        this.versions = versions;
        this.artifacts = artifacts;
        this.storage = storage;
        this.devices = devices;
        this.audit = audit;
        this.signing = signing;
        this.scanService = scanService;
        this.review = review;
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
        Set<String> paths = new HashSet<>();
        for (FileSpec file : files) {
            requireSafePath(file.path());
            // 同一个相对路径只能有一个文件：重名会让打出来的包里有重名 entry，
            // 清单、审核面板、客户端安装器对同一路径可能各取一份字节。
            if (!paths.add(file.path())) {
                throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                    "变更集里有重名的文件：" + file.path());
            }
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
        SkillEntity skill = skills.findById(changeset.getSkillId())
            .orElseThrow(() -> ApiException.notFound("Skill"));
        // 同步提交也是发版，跟整包上传走同一道发布权闸（组织角色 publisher 及以上）：
        // 只查 canView 的话，团队的普通成员也能往待审队列里塞一版内容。
        // 个人 Skill 没有组织角色，requirePublishRight 放行（与整包上传同一口径）。
        skillService.requirePublishRight(user, skill);
        // 锁住 Skill 行再读基线、算版本号：并发提交会排队，
        // 不会各读各的基线后各写一版，撞 UNIQUE(skill_id, version) 变成 500。
        // 锁的顺序是「先版本行、后 Skill 行」——这里不锁别人的版本行，只锁 Skill 行。
        SkillEntity locked = skills.findByIdForUpdate(changeset.getSkillId()).orElse(skill);
        List<SkillVersionEntity> rows =
            versions.findBySkillIdOrderByCreatedAtDesc(changeset.getSkillId());
        String cloud = cloudRevision(rows);
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
        // 内容寻址落桶：同一份内容在桶里只留一份。
        // 早先这里按 changeset 拼 key（sync/<changesetId>/<sha>.zip），
        // 每次提交都写一个新对象——两台设备各提交一次同一份内容，
        // 桶里就多出一份没人删的孤儿，而库里只有一条记录指向第一个。
        String finalKey = artifacts.store(sha256, zip);

        ArtifactEntity artifact = artifacts.record(sha256, finalKey, zip.length, files.size(),
            manifestJson(files));
        String signature = signing.sign(ContentDigest.of(manifestJson(files)));
        if (!signature.isEmpty()) {
            artifact.setSignature(signature);
            artifact.setSignatureKeyId(signing.keyId());
            artifacts.saveArtifact(artifact);
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SkillVersionEntity version = new SkillVersionEntity();
        version.setId(UUID.randomUUID());
        version.setSkillId(skill.getId());
        version.setVersion(nextVersion(locked.getLatestVersion(), rows));
        version.setChannel("stable");
        version.setChangelog("同步提交 " + changesetId);
        version.setManifestJson(manifestJson(files));
        version.setArtifactId(artifact.getId());
        version.setStatus("draft");
        version.setCreatedBy(user.id());
        version.setCreatedAt(now);
        // 先落库：扫描行有指向 skill_versions 的外键。
        versions.save(version);

        // 同步路径与整包上传走同一道服务端扫描——绕开哪条都不该绕开它。
        ScanResult scan = scanService.scanAndRecord(version.getId(), zip);
        // 团队与组织可见的版本要等人审，跟整包上传一个口径：
        // 走 sync commit 不能把审核队列绕过去。
        boolean needsReview = review.needsReview(skill.getId());
        version.setStatus(scan.blocked() ? "rejected" : needsReview ? "pending_review" : "published");
        if ("published".equals(version.getStatus())) {
            version.setPublishedBy(user.id());
            version.setPublishedAt(now);
            // latest_version 取版本号较大者：发一版旧的不能把 latest 顶回去。
            if (VersionOrder.isGreater(version.getVersion(), skill.getLatestVersion())) {
                skill.setLatestVersion(version.getVersion());
            }
            skill.setUpdatedAt(now);
            skills.save(skill);
        }
        if ("pending_review".equals(version.getStatus())) {
            // 队列里的 submitted_at 是进队列这一刻，不是建版本那一刻。
            version.setSubmittedAt(now);
        }
        versions.save(version);

        changeset.setStatus("completed");
        changeset.setTargetRevision(ContentDigest.of(manifestJson(files)));
        changeset.setCompletedAt(now);
        changesets.save(changeset);

        audit.record(user, "sync.commit", "skill", skill.getId(), "success",
            Map.of("version", version.getVersion(), "files", String.valueOf(files.size()),
                "status", version.getStatus(), "scan_status", scan.status()));
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
    private String cloudRevision(List<SkillVersionEntity> rows) {
        for (SkillVersionEntity version : rows) {
            if ("published".equals(version.getStatus())
                && version.getManifestJson() != null && !version.getManifestJson().isEmpty()) {
                return ContentDigest.of(version.getManifestJson());
            }
        }
        return "";
    }

    /**
     * 下一个版本号：在 latest_version 上加一，且不撞这个 Skill 已有的版本号。
     *
     * 已有的版本号包括待审与草稿：待审的版本没进 latest_version，
     * 只看 latest_version 会算出同一个号，撞 UNIQUE(skill_id, version) 变成 500。
     */
    private String nextVersion(String latest, List<SkillVersionEntity> rows) {
        Set<String> used = new HashSet<>();
        for (SkillVersionEntity row : rows) {
            used.add(row.getVersion());
        }
        String candidate = bumpVersion(latest);
        while (used.contains(candidate)) {
            candidate = bumpVersion(candidate);
        }
        return candidate;
    }

    /** 版本号加一：语义化版本加 patch 位，不是语义化版本就加 -sync 后缀。 */
    private String bumpVersion(String latest) {
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
