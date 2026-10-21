package com.skillmesh.api.upload;

import com.skillmesh.api.artifact.ArtifactEntity;
import com.skillmesh.api.artifact.SigningService;
import com.skillmesh.api.common.ContentDigest;
import com.skillmesh.api.review.ReviewService;
import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.artifact.ArtifactService;
import com.skillmesh.api.artifact.ZipManifest;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.scan.ArtifactScanService;
import com.skillmesh.api.scan.ScanResult;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.version.SkillVersionEntity;
import com.skillmesh.api.version.SkillVersionRepository;
import com.skillmesh.api.version.dto.UploadSession;
import java.io.IOException;
import java.io.InputStream;
import com.skillmesh.api.artifact.ArtifactStorage;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
    private final ArtifactStorage storage;
    private final ArtifactScanService scanService;
    private final AuditService audit;
    private final SigningService signing;
    private final ReviewService review;

    public UploadService(UploadSessionRepository uploads, SkillVersionRepository versions,
                         SkillRepository skills, ArtifactService artifacts, ArtifactStorage storage,
                         ArtifactScanService scanService, AuditService audit,
                         SigningService signing, ReviewService review) {
        this.uploads = uploads;
        this.versions = versions;
        this.skills = skills;
        this.artifacts = artifacts;
        this.storage = storage;
        this.scanService = scanService;
        this.audit = audit;
        this.signing = signing;
        this.review = review;
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

        if ("sync_file".equals(session.getPurpose())) {
            // 同步文件只校验，不转正成制品——等 changeset commit 时再打包
            // （详细设计 §2.4.2 第 3 步）。
            return completeSyncFile(session, now);
        }

        SkillVersionEntity version = versions.findById(session.getSkillVersionId())
            .orElseThrow(() -> ApiException.notFound("版本"));
        session.setStatus("verifying");
        uploads.save(session);

        String finalKey = artifacts.finalKey(version.getSkillId(), version.getVersion(),
            session.getExpectedSha256());
        ArtifactService.Promoted promoted;
        byte[] content;
        List<Map<String, Object>> manifest;
        try {
            promoted = artifacts.promote(session.getTemporaryStorageKey(), finalKey,
                session.getExpectedSha256(), session.getExpectedSizeBytes());
            // 逐文件清单由服务端从真 zip 算：发布方声明的那份只是他的说法，不能当准。
            // 算清单的同时验包：重名 entry 的包是非法制品，不能变成正式制品。
            content = readArtifact(finalKey);
            manifest = ZipManifest.of(content);
        } catch (ApiException e) {
            session.setStatus("failed");
            session.setErrorCode(e.code());
            session.setVerifiedAt(now);
            uploads.save(session);
            throw e;
        }

        ArtifactEntity artifact = artifacts.record(promoted.sha256(), finalKey,
            promoted.sizeBytes(), manifest.size(), manifest);
        signArtifact(artifact, version);

        version.setArtifactId(artifact.getId());
        ScanResult scan = scanService.scanAndRecord(version.getId(), content);
        // 个人与公开的版本扫描通过就发布；团队与组织可见的要等人审
        // （设计文档 §10.1 的 draft → scanning → pending_review → published）。
        boolean needsReview = review.needsReview(version.getSkillId());
        version.setStatus(scan.blocked() ? "rejected" : needsReview ? "pending_review" : "published");
        if ("published".equals(version.getStatus())) {
            version.setPublishedBy(user.id());
            version.setPublishedAt(now);
        }
        versions.save(version);

        if ("published".equals(version.getStatus())) {
            skills.findById(version.getSkillId()).ifPresent(skill -> {
                skill.setLatestVersion(version.getVersion());
                skill.setUpdatedAt(now);
                skills.save(skill);
            });
        }

        String action = switch (version.getStatus()) {
            case "rejected" -> "skill.publish_rejected";
            case "pending_review" -> "skill.submit_for_review";
            default -> "skill.publish";
        };
        audit.record(user, action, "skill", version.getSkillId(),
            "rejected".equals(version.getStatus()) ? "failure" : "success",
            Map.of("version", version.getVersion(), "sha256", promoted.sha256(),
                "scan_status", scan.status()));

        session.setStatus("completed");
        session.setVerifiedAt(now);
        session.setUploadedAt(now);
        uploads.save(session);

        return new UploadSession(session.getId(), session.getPurpose(), "completed", null,
            session.getExpiresAt(), promoted.sha256(), null);
    }

    /** 同步文件上传的收尾：校验字节数与摘要，通过就等着 commit。 */
    private UploadSession completeSyncFile(UploadSessionEntity session, OffsetDateTime now) {
        session.setStatus("verifying");
        uploads.save(session);
        try {
            int size = storage.get(session.getTemporaryStorageKey()).readAllBytes().length;
            if (session.getExpectedSizeBytes() > 0 && size != session.getExpectedSizeBytes()) {
                throw new ApiException(422, ErrorCodes.DIGEST_MISMATCH,
                    "文件大小与声明不一致：声明 " + session.getExpectedSizeBytes()
                        + "，实际 " + size);
            }
            session.setStatus("completed");
            session.setVerifiedAt(now);
            uploads.save(session);
            return new UploadSession(session.getId(), session.getPurpose(), "completed", null,
                session.getExpiresAt(), session.getExpectedSha256(), session.getRelativePath());
        } catch (ApiException e) {
            session.setStatus("failed");
            session.setErrorCode(e.code());
            uploads.save(session);
            throw e;
        } catch (java.io.IOException e) {
            session.setStatus("failed");
            session.setErrorCode(ErrorCodes.ARTIFACT_MISSING);
            uploads.save(session);
            throw new ApiException(502, ErrorCodes.ARTIFACT_MISSING,
                "读取上传内容失败：" + e.getMessage());
        }
    }

    /**
     * 读回刚转正的制品。读不出来就没法算清单也没法扫——直接报错。
     */
    private byte[] readArtifact(String key) {
        try (InputStream in = storage.get(key)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new ApiException(502, ErrorCodes.ARTIFACT_MISSING,
                "读取上传内容失败：" + e.getMessage());
        }
    }

    /**
     * 给制品的清单摘要签名（设计文档 §10 制品完整性）。
     * 没配私钥时 sign 返回空串，等于不签——空签名不会被误当成「验过了」。
     */
    private void signArtifact(ArtifactEntity artifact, SkillVersionEntity version) {
        String signature = signing.sign(ContentDigest.of(version.getManifestJson()));
        if (signature.isEmpty()) {
            return;
        }
        artifact.setSignature(signature);
        artifact.setSignatureKeyId(signing.keyId());
        artifacts.saveArtifact(artifact);
    }
}
