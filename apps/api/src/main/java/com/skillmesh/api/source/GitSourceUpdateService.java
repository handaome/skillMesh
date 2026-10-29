package com.skillmesh.api.source;

import com.skillmesh.api.artifact.ArtifactStorage;
import com.skillmesh.api.artifact.SkillPackager;
import com.skillmesh.api.artifact.ZipManifest;
import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ContentDigest;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.common.SafePath;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillService;
import com.skillmesh.api.skill.SourceEntity;
import com.skillmesh.api.skill.SourceRepository;
import com.skillmesh.api.source.dto.SkillSourceResponse;
import com.skillmesh.api.source.dto.SourceUpdateCheckResponse;
import com.skillmesh.api.source.dto.SourceUpdateResultResponse;
import com.skillmesh.api.upload.UploadService;
import com.skillmesh.api.upload.UploadSessionEntity;
import com.skillmesh.api.upload.UploadSessionRepository;
import com.skillmesh.api.version.SkillVersionEntity;
import com.skillmesh.api.version.SkillVersionRepository;
import com.skillmesh.api.version.SkillVersionService;
import com.skillmesh.api.version.VersionNumberGenerator;
import com.skillmesh.api.version.VersionOrder;
import com.skillmesh.api.version.dto.CreateVersionRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 从 Git 来源拉取新提交，产出**新版本**。
 *
 * 走的是和 CLI 发布完全一样的链路：建候选版本 → 服务端扫描 →
 * 个人与公开直接发布，团队与组织进待审队列。绕开扫描那道闸，
 * 网页端就成了发布链路的旁路——所以这里复用
 * {@link SkillVersionService#create} 与 {@link UploadService#complete}，
 * 不另写一套发布逻辑。
 *
 * 只改云端的内容。本机装的那份得靠 CLI 的 {@code skillmesh update} 拉回去：
 * 服务端看不到任何人的磁盘。
 */
@Service
public class GitSourceUpdateService {

    private final SourceService sources;
    private final SourceRepository sourceRepository;
    private final SkillService skillService;
    private final SkillVersionService versionService;
    private final SkillVersionRepository versions;
    private final UploadSessionRepository uploads;
    private final ArtifactStorage storage;
    private final UploadService uploadService;
    private final AuditService audit;

    public GitSourceUpdateService(SourceService sources, SourceRepository sourceRepository,
                                  SkillService skillService, SkillVersionService versionService,
                                  SkillVersionRepository versions, UploadSessionRepository uploads,
                                  ArtifactStorage storage, UploadService uploadService,
                                  AuditService audit) {
        this.sources = sources;
        this.sourceRepository = sourceRepository;
        this.skillService = skillService;
        this.versionService = versionService;
        this.versions = versions;
        this.uploads = uploads;
        this.storage = storage;
        this.uploadService = uploadService;
        this.audit = audit;
    }

    /** 这个 Skill 的 Git 来源。 */
    public SkillSourceResponse info(CurrentUser user, UUID skillId) {
        skillService.requireVisibleSkill(user, skillId);
        SourceEntity source = requireGitSource(skillId);
        return new SkillSourceResponse(source.getSourceType(), source.getProvider(),
            source.getRepositoryUrl(), source.getSubpath(), source.getRefType(),
            source.getRefValue(), source.getResolvedCommit());
    }

    /**
     * 查远端有没有新提交。只查 ref，不下载内容。
     */
    public SourceUpdateCheckResponse check(CurrentUser user, UUID skillId) {
        skillService.requireVisibleSkill(user, skillId);
        SourceEntity source = requireGitSource(skillId);
        String ref = refOf(source);
        SourceService.UpdateCheck remote = sources.checkUpdates(source.getRepositoryUrl(), ref);
        String current = source.getResolvedCommit() == null ? "" : source.getResolvedCommit();
        boolean hasUpdate = !remote.commit().isBlank() && !remote.commit().equals(current);
        return new SourceUpdateCheckResponse(source.getRepositoryUrl(), ref, current,
            remote.commit(), hasUpdate);
    }

    /**
     * 拉取新提交并产出新版本。
     *
     * 权限与发版一致：private/public 只有属主能发，团队与组织要 publisher 及以上。
     * 从 Git 拉新版就是发版，门槛不能比发版松。
     */
    public SourceUpdateResultResponse update(CurrentUser user, UUID skillId) {
        SkillEntity skill = skillService.requireVisibleSkill(user, skillId);
        skillService.requirePublishRight(user, skill);
        SourceEntity source = requireGitSource(skillId);

        try (SourceService.Checkout checkout =
                 sources.checkout(source.getRepositoryUrl(), refOf(source))) {
            Path skillDir = locate(checkout.root(), source.getSubpath());
            byte[] zip = SkillPackager.pack(skillDir);
            List<Map<String, Object>> manifest = ZipManifest.of(zip);
            String sha256 = ContentDigest.sha256(zip);
            String version = nextVersion(skillId);

            var created = versionService.create(user, skillId, new CreateVersionRequest(
                version, "stable", source.getId(), checkout.commit(),
                "从 Git 来源更新：" + source.getRepositoryUrl() + "@"
                    + shortCommit(checkout.commit()),
                zip.length, manifest.size(), sha256, toManifest(manifest), null));

            // 内容在服务端生成，没有客户端要传的字节：直接写进临时位置，
            // 再走 complete 转正——扫描、发布还是送审由 complete 判定。
            UploadSessionEntity session = uploads.findById(created.upload().sessionId())
                .orElseThrow(() -> ApiException.notFound("上传会话"));
            storage.put(session.getTemporaryStorageKey(), zip, "application/zip");
            var completed = uploadService.complete(user, session.getId());

            source.setResolvedCommit(checkout.commit());
            source.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            sourceRepository.save(source);

            audit.record(user, "skill.update_from_git", "skill", skillId, "success",
                Map.of("version", version, "commit", checkout.commit(),
                    "sha256", sha256, "status", completed.versionStatus()));

            return new SourceUpdateResultResponse(version, checkout.commit(), sha256,
                manifest.size(), completed.versionStatus());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, ErrorCodes.INTERNAL,
                "从 Git 来源更新失败：" + e.getMessage());
        }
    }

    /** 找这个 Skill 的 Git 来源记录。 */
    private SourceEntity requireGitSource(UUID skillId) {
        for (SourceEntity source : sourceRepository.findBySkillIdOrderByCreatedAtDesc(skillId)) {
            if ("git".equals(source.getSourceType()) && source.getRepositoryUrl() != null
                && !source.getRepositoryUrl().isBlank()) {
                return source;
            }
        }
        throw ApiException.notFound("这个 Skill 没有 Git 来源");
    }

    /**
     * 要取的 ref。
     *
     * 固定到 commit 的来源不跟着分支走：取的就是那个 commit 本身。
     */
    private String refOf(SourceEntity source) {
        String ref = source.getRefValue();
        return ref == null ? "" : ref.trim();
    }

    /** 定位仓库里的 Skill 目录。 */
    private Path locate(Path root, String subpath) {
        Path dir = root;
        if (subpath != null && !subpath.isBlank()) {
            if (SafePath.escapes(subpath)) {
                throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "子目录越界");
            }
            dir = root.resolve(subpath).normalize();
            if (!dir.startsWith(root)) {
                throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "子目录越界");
            }
        }
        if (!Files.isDirectory(dir) || !Files.exists(dir.resolve("SKILL.md"))) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "仓库里找不到 Skill 目录" + (subpath == null || subpath.isBlank()
                    ? "" : "：" + subpath));
        }
        return dir;
    }

    /**
     * 新版本号。
     *
     * 撞号要避开：待审与草稿的版本号没进 latest_version，
     * 只看 latest_version 会算出同一个号，撞唯一约束就是 500。
     */
    private String nextVersion(UUID skillId) {
        List<SkillVersionEntity> rows = versions.findBySkillIdOrderByCreatedAtDesc(skillId);
        String latest = null;
        Set<String> used = new HashSet<>();
        for (SkillVersionEntity row : rows) {
            used.add(row.getVersion());
            if (latest == null || VersionOrder.isGreater(row.getVersion(), latest)) {
                latest = row.getVersion();
            }
        }
        return VersionNumberGenerator.next(latest, used);
    }

    private List<CreateVersionRequest.ManifestEntry> toManifest(List<Map<String, Object>> manifest) {
        List<CreateVersionRequest.ManifestEntry> entries = new ArrayList<>();
        for (Map<String, Object> item : manifest) {
            entries.add(CreateVersionRequest.ManifestEntry.from(item));
        }
        return entries;
    }

    private String shortCommit(String commit) {
        return commit == null || commit.length() <= 8 ? commit : commit.substring(0, 8);
    }
}
