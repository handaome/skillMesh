package com.skillmesh.api.upload;

import com.skillmesh.api.artifact.ArtifactEntity;
import com.skillmesh.api.artifact.SigningService;
import com.skillmesh.api.common.ContentDigest;
import com.skillmesh.api.recycle.RecycleService;
import com.skillmesh.api.review.ReviewService;
import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.artifact.ArtifactService;
import com.skillmesh.api.artifact.ZipManifest;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.scan.ArtifactScanService;
import com.skillmesh.api.scan.ScanResult;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.version.SkillVersionEntity;
import com.skillmesh.api.version.SkillVersionRepository;
import com.skillmesh.api.version.PublishedDigest;
import com.skillmesh.api.version.VersionOrder;
import com.skillmesh.api.version.dto.UploadSession;
import java.io.IOException;
import java.io.InputStream;
import com.skillmesh.api.artifact.ArtifactStorage;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final RecycleService recycle;
    private final JdbcTemplate jdbc;

    public UploadService(UploadSessionRepository uploads, SkillVersionRepository versions,
                         SkillRepository skills, ArtifactService artifacts, ArtifactStorage storage,
                         ArtifactScanService scanService, AuditService audit,
                         SigningService signing, ReviewService review, RecycleService recycle,
                         JdbcTemplate jdbc) {
        this.uploads = uploads;
        this.versions = versions;
        this.skills = skills;
        this.artifacts = artifacts;
        this.storage = storage;
        this.scanService = scanService;
        this.audit = audit;
        this.signing = signing;
        this.review = review;
        this.recycle = recycle;
        this.jdbc = jdbc;
    }

    /**
     * 把 latest_version 推到更大的那个版本。
     *
     * 用 JdbcTemplate 直接读写，**不经过 Hibernate 实体**：
     * Skill 实体可能已被懒加载进持久化上下文，`findByIdForUpdate` 返回的是
     * 一级缓存里的旧实例（SQL 根本没执行），两个并发发布就各读各的旧值，
     * 后写的把 latest 顶回旧版本——「最新版本」退回旧的一版。
     *
     * 这里读的是数据库里此刻的值，比的是 semver。
     * Skill 行在 complete 开头已经锁住（先 Skill 行、后版本行），这里不再重复加锁。
     */
    private void advanceLatestVersion(UUID skillId, String version, OffsetDateTime now) {
        // 不带 for update：这一行在 complete 开头已经锁住了（先 Skill 行、
        // 后版本行，全局同一顺序）。这里再锁一次会与并发发布构成死锁环。
        List<String> current = jdbc.query(
            "select latest_version from skills where id = ?",
            (rs, rowNum) -> rs.getString(1), skillId);
        if (current.isEmpty()) {
            return;
        }
        // latest_version 取版本号较大者：发一版旧的不能把 latest 顶回去。
        if (!VersionOrder.isGreater(version, current.get(0))) {
            return;
        }
        jdbc.update("update skills set latest_version = ?, updated_at = ? where id = ?",
            version, now, skillId);
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

        // 锁的顺序全局统一：**先 Skill 行、后版本行**。
        // 建版本（SkillVersionService.create）也是这个顺序——两条路径一致才不会死锁。
        // 反过来（先版本行后 Skill 行）时，两个并发发布各持一半，锁成环。
        //
        // 先把版本读出来拿 skill_id（不锁），锁住 Skill 行之后再锁版本行。
        // 后面改 latest_version 时直接用这个锁：最新值要用数据库里此刻的值
        // （JdbcTemplate 读，不走 Hibernate 的一级缓存），不能拿实体里的旧值。
        SkillVersionEntity unlocked = versions.findById(session.getSkillVersionId())
            .orElseThrow(() -> ApiException.notFound("版本"));
        skills.findByIdForUpdate(unlocked.getSkillId());
        SkillVersionEntity version = versions.findByIdForUpdate(session.getSkillVersionId())
            .orElseThrow(() -> ApiException.notFound("版本"));
        // 撤回不可逆：撤回了的版本不能再被上传会话转成已发布或待审。
        // 撤回与收尾锁同一行，两个动作不会交错。
        if ("revoked".equals(version.getStatus()) || "yanked".equals(version.getStatus())) {
            session.setStatus("failed");
            session.setErrorCode(ErrorCodes.VERSION_CONFLICT);
            uploads.save(session);
            throw new ApiException(409, ErrorCodes.VERSION_CONFLICT,
                "版本已撤回，不能再发布");
        }
        // 基线复检：建版本到转正之间，云端可能被别人动过。
        // 建版本那一刻查过一次，但那个窗口没被覆盖——这次转正会把
        // 那份改动静默盖掉。这里再对一次，对不上就让客户端重新对账。
        requireBaseRevision(version);
        session.setStatus("verifying");
        uploads.save(session);

        ArtifactService.Promoted promoted;
        byte[] content;
        List<Map<String, Object>> manifest;
        boolean manifestMatches;
        try {
            promoted = artifacts.promote(session.getTemporaryStorageKey(),
                session.getExpectedSha256(), session.getExpectedSizeBytes());
            // 逐文件清单由服务端从真 zip 算：发布方声明的那份只是他的说法，不能当准。
            // 算清单的同时验包：重名 entry 的包是非法制品，不能变成正式制品。
            content = readArtifact(promoted.storageKey());
            manifest = ZipManifest.of(content);
            // 详细设计 §13：服务端重新计算归档内实际路径和哈希，并与上传声明比对。
            // 比对的结果是用真值——声明跟真包不符也照收，但记一笔，
            // 免得发布方以为声明说了算，审核面板却列着另一份文件清单。
            manifestMatches = sameFiles(version.getManifestJson(), manifest);
            // 换成真清单：签的摘要、对外发的 content_digest、
            // 审核面板列的文件，都是包里真实的那份。
            version.setManifestJson(manifest);
        } catch (ApiException e) {
            session.setStatus("failed");
            session.setErrorCode(e.code());
            session.setVerifiedAt(now);
            uploads.save(session);
            throw e;
        }

        ArtifactEntity artifact = artifacts.record(promoted.sha256(), promoted.storageKey(),
            promoted.sizeBytes(), manifest.size(), manifest);
        signArtifact(artifact, version);

        version.setArtifactId(artifact.getId());
        ScanResult scan = scanService.scanAndRecord(version.getId(), content);
        // 个人与公开的版本扫描通过就发布；团队与组织可见的要等人审
        // （设计文档 §10.1 的 draft → scanning → pending_review → published）。
        boolean needsReview = review.needsReview(version.getSkillId());
        // 扫描命中阻断规则 -> quarantined（隔离，不是普通的拒绝）：
        // 「内容里有真凭据」与「审核者觉得不合适」是两回事，流水要能分开看
        // （设计文档 §10.1：扫描失败或审核拒绝进入 rejected/quarantined）。
        version.setStatus(scan.blocked() ? "quarantined" : needsReview ? "pending_review" : "published");
        if ("published".equals(version.getStatus())) {
            version.setPublishedBy(user.id());
            version.setPublishedAt(now);
        }
        if ("pending_review".equals(version.getStatus())) {
            // 队列里的 submitted_at 是进队列这一刻，不是建版本那一刻。
            version.setSubmittedAt(now);
        }
        versions.save(version);

        if ("published".equals(version.getStatus())) {
            // 锁住这一行再改 latest_version：不锁的话两个并发发布会各读各的，
            // 后写的把先写的顶掉，latest 就退回了旧版本。
            advanceLatestVersion(version.getSkillId(), version.getVersion(), now);
            // 这一版比上一版少的文件进回收站（设计文档 §6.4）：
            // 删掉不是抹掉，回收期内能找回来。
            recycle.recordRemoved(version.getSkillId(), version.getVersion());
        }

        String action = switch (version.getStatus()) {
            case "rejected" -> "skill.publish_rejected";
            case "quarantined" -> "skill.publish_quarantined";
            case "pending_review" -> "skill.submit_for_review";
            default -> "skill.publish";
        };
        audit.record(user, action, "skill", version.getSkillId(),
            "rejected".equals(version.getStatus()) || "quarantined".equals(version.getStatus())
                ? "failure" : "success",
            Map.of("version", version.getVersion(), "sha256", promoted.sha256(),
                "scan_status", scan.status(),
                // 声明的清单跟真包对不上：收下了，但记一笔——发布方说的不算数。
                "manifest_mismatch", !manifestMatches));

        session.setStatus("completed");
        session.setVerifiedAt(now);
        session.setUploadedAt(now);
        uploads.save(session);

        // 版本状态一并带回去：会话的 status 只说「上传完了没有」，
        // 客户端要判断的是这版被服务端怎么处理了（扫描拦下 / 待审 / 已发布）。
        return new UploadSession(session.getId(), session.getPurpose(), "completed", null,
            session.getExpiresAt(), promoted.sha256(), null, version.getStatus());
    }

    /**
     * 基线复检：建版本时带的云端摘要，现在还对得上吗。
     *
     * 建版本那一刻查过一次，但那次查只覆盖了「查的那一瞬间」。
     * 从建版本到转正（上传完成）之间，云端可能被别人发布了新版本——
     * 这次转正会把那份改动静默盖掉。转正前再对一次，对不上就让客户端重新对账。
     *
     * 只查这一版自己的基线：没带基线的（普通发布、网页端编辑）照旧放行。
     * 锁 Skill 行再读已发布版本：和并发发布串行，否则读到的是半路状态。
     */
    private void requireBaseRevision(SkillVersionEntity version) {
        String expected = version.getBaseRevision();
        if (expected == null || expected.isBlank()) {
            return;
        }
        // complete 开头已经锁过这一行；这里再锁一次是同事务重复加锁，立即返回。
        // 留着是为了这个方法自洽——它不依赖调用方已经持锁。
        skills.findByIdForUpdate(version.getSkillId());
        String cloud = PublishedDigest.of(
            versions.findBySkillIdOrderByCreatedAtDescThenId(version.getSkillId()));
        if (!expected.equals(cloud)) {
            throw new ApiException(409, ErrorCodes.VERSION_CONFLICT,
                "云端已被别人改动（你看到的是 " + ContentDigest.shortDigest(expected) + "，现在是 "
                    + ContentDigest.shortDigest(cloud) + "），请重新对账");
        }
    }

    /**
     * 两份清单是不是同一批文件（路径 + 摘要一致）。
     *
     * 声明的那份是发布方自己填的，真包那份是服务端算出来的——对不上就说明
     * 声明不可信，以真包为准。没声明清单就当对不上（等于没填）。
     */
    private boolean sameFiles(List<Map<String, Object>> declared,
                              List<Map<String, Object>> actual) {
        if (declared == null || declared.isEmpty()) {
            return false;
        }
        return filesOf(declared).equals(filesOf(actual));
    }

    /** 清单里的文件：路径 → 摘要。 */
    private Map<String, String> filesOf(List<Map<String, Object>> manifest) {
        Map<String, String> files = new HashMap<>();
        for (Map<String, Object> item : manifest) {
            files.put(String.valueOf(item.get("path")), String.valueOf(item.get("sha256")));
        }
        return files;
    }

    /** 同步文件上传的收尾：校验字节数与摘要，通过就等着 commit。 */
    private UploadSession completeSyncFile(UploadSessionEntity session, OffsetDateTime now) {
        session.setStatus("verifying");
        uploads.save(session);
        try {
            byte[] data = storage.get(session.getTemporaryStorageKey()).readAllBytes();
            int size = data.length;
            if (session.getExpectedSizeBytes() > 0 && size != session.getExpectedSizeBytes()) {
                throw new ApiException(422, ErrorCodes.DIGEST_MISMATCH,
                    "文件大小与声明不一致：声明 " + session.getExpectedSizeBytes()
                        + "，实际 " + size);
            }
            // 摘要也得对：整包上传那条路会重算并比对（ArtifactService.promote），
            // 这里只比字节数的话，传输损坏或客户端算错都能蒙过去——
            // 而响应里的 verified_sha256 回显的是声明值，从没被验证过。
            String actual = ContentDigest.sha256(data);
            if (session.getExpectedSha256() != null && !session.getExpectedSha256().isBlank()
                && !actual.equals(session.getExpectedSha256())) {
                throw new ApiException(422, ErrorCodes.DIGEST_MISMATCH,
                    "文件摘要与声明不一致：声明 " + session.getExpectedSha256()
                        + "，实际 " + actual);
            }
            session.setStatus("completed");
            session.setVerifiedAt(now);
            uploads.save(session);
            return new UploadSession(session.getId(), session.getPurpose(), "completed", null,
                session.getExpiresAt(), actual, session.getRelativePath(), null);
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
