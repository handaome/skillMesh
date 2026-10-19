package com.skillmesh.api.upload;

import com.skillmesh.api.artifact.ArtifactEntity;
import com.skillmesh.api.artifact.SigningService;
import com.skillmesh.api.common.ContentDigest;
import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.artifact.ArtifactService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.scan.ArtifactScanner;
import com.skillmesh.api.scan.ScanEntity;
import com.skillmesh.api.scan.ScanRepository;
import com.skillmesh.api.scan.ScanResult;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.version.SkillVersionEntity;
import com.skillmesh.api.version.SkillVersionRepository;
import com.skillmesh.api.version.dto.UploadSession;
import java.io.InputStream;
import com.skillmesh.api.artifact.ArtifactStorage;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private final ArtifactScanner scanner;
    private final ScanRepository scans;
    private final AuditService audit;
    private final SigningService signing;

    public UploadService(UploadSessionRepository uploads, SkillVersionRepository versions,
                         SkillRepository skills, ArtifactService artifacts, ArtifactStorage storage,
                         ArtifactScanner scanner, ScanRepository scans, AuditService audit,
                         SigningService signing) {
        this.uploads = uploads;
        this.versions = versions;
        this.skills = skills;
        this.artifacts = artifacts;
        this.storage = storage;
        this.scanner = scanner;
        this.scans = scans;
        this.audit = audit;
        this.signing = signing;
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
        signArtifact(artifact, version);

        version.setArtifactId(artifact.getId());
        ScanResult scan = runScan(version, storage.get(finalKey));
        version.setStatus(scan.blocked() ? "rejected" : "published");
        if (!scan.blocked()) {
            version.setPublishedBy(user.id());
            version.setPublishedAt(now);
        }
        versions.save(version);

        if (!scan.blocked()) {
            skills.findById(version.getSkillId()).ifPresent(skill -> {
                skill.setLatestVersion(version.getVersion());
                skill.setUpdatedAt(now);
                skills.save(skill);
            });
        }

        audit.record(user, scan.blocked() ? "skill.publish_rejected" : "skill.publish",
            "skill", version.getSkillId(), scan.blocked() ? "failure" : "success",
            Map.of("version", version.getVersion(), "sha256", promoted.sha256(),
                "scan_status", scan.status()));

        session.setStatus("completed");
        session.setVerifiedAt(now);
        session.setUploadedAt(now);
        uploads.save(session);

        return new UploadSession(session.getId(), session.getPurpose(), "completed", null,
            session.getExpiresAt(), promoted.sha256(), null);
    }

    /**
     * 制品定版之后立刻扫一遍，结论落 skill_scans。
     * 命中阻断规则就不发布——服务端这份才是准的，绕开 CLI 上传也一样（设计文档 §10）。
     */
    private ScanResult runScan(SkillVersionEntity version, InputStream content) {
        ScanResult result = scanner.scan(content);
        OffsetDateTime completed = OffsetDateTime.now(ZoneOffset.UTC);
        ScanEntity row = new ScanEntity();
        row.setId(UUID.randomUUID());
        row.setSkillVersionId(version.getId());
        row.setScannerName(ArtifactScanner.NAME);
        row.setScannerVersion(ArtifactScanner.VERSION);
        row.setStatus(result.status());
        row.setFindingsJson(toJson(result.findings()));
        row.setSummary(result.summary());
        row.setStartedAt(completed.minusNanos(result.durationMs() * 1_000_000L));
        row.setCompletedAt(completed);
        scans.save(row);
        return result;
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

    private List<Map<String, Object>> toJson(List<ScanResult.Finding> findings) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ScanResult.Finding finding : findings) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", finding.path());
            entry.put("line", finding.line());
            entry.put("rule", finding.rule());
            entry.put("detail", finding.detail());
            out.add(entry);
        }
        return out;
    }
}
