package com.skillmesh.api.source;

import com.skillmesh.api.artifact.ArtifactStorage;
import com.skillmesh.api.artifact.SkillPackager;
import com.skillmesh.api.artifact.ZipManifest;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ContentDigest;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.upload.UploadService;
import com.skillmesh.api.upload.UploadSessionEntity;
import com.skillmesh.api.upload.UploadSessionRepository;
import com.skillmesh.api.version.SkillVersionEntity;
import com.skillmesh.api.version.SkillVersionRepository;
import com.skillmesh.api.version.SkillVersionService;
import com.skillmesh.api.version.VersionNumberGenerator;
import com.skillmesh.api.version.VersionOrder;
import com.skillmesh.api.version.dto.CreateVersionRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 把仓库里检出的 Skill 目录变成云端的一个版本。
 *
 * 「从 Git 更新」和「从 Git 导入」共用这一段：两者的差别只是
 * Skill 与来源记录是先有还是后有，产出版本的那几步完全一样。
 *
 * 产出走的是和 CLI 发布完全一样的链路：建候选版本 → 服务端扫描 →
 * 个人与公开直接发布，团队与组织进待审队列。绕开扫描那道闸，
 * 网页端就成了发布链路的旁路——所以这里复用
 * {@link SkillVersionService#create} 与 {@link UploadService#complete}，
 * 不另写一套发布逻辑。
 */
@Service
public class GitArtifactPublisher {

    private final SkillVersionService versionService;
    private final SkillVersionRepository versions;
    private final UploadSessionRepository uploads;
    private final ArtifactStorage storage;
    private final UploadService uploadService;

    public GitArtifactPublisher(SkillVersionService versionService,
                                SkillVersionRepository versions,
                                UploadSessionRepository uploads,
                                ArtifactStorage storage,
                                UploadService uploadService) {
        this.versionService = versionService;
        this.versions = versions;
        this.uploads = uploads;
        this.storage = storage;
        this.uploadService = uploadService;
    }

    /**
     * 打包并发布。
     *
     * version 为 null 时按已有的最大版本号加一；新 Skill 传 "1.0.0"。
     */
    public Published publish(CurrentUser user, UUID skillId, UUID sourceId, String commit,
                             Path skillDir, String version, String changelog) {
        byte[] zip;
        List<Map<String, Object>> manifest;
        try {
            zip = SkillPackager.pack(skillDir);
            manifest = ZipManifest.of(zip);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(500, ErrorCodes.INTERNAL,
                "打包仓库内容失败：" + e.getMessage());
        }
        String sha256 = ContentDigest.sha256(zip);
        String versionNumber = version == null || version.isBlank()
            ? nextVersion(skillId) : version;

        var created = versionService.create(user, skillId, new CreateVersionRequest(
            versionNumber, "stable", sourceId, commit,
            changelog == null ? "" : changelog,
            zip.length, manifest.size(), sha256, toManifest(manifest), null));

        // 内容在服务端生成，没有客户端要传的字节：直接写进临时位置，
        // 再走 complete 转正——扫描、发布还是送审由 complete 判定。
        UploadSessionEntity session = uploads.findById(created.upload().sessionId())
            .orElseThrow(() -> ApiException.notFound("上传会话"));
        storage.put(session.getTemporaryStorageKey(), zip, "application/zip");
        var completed = uploadService.complete(user, session.getId());

        return new Published(versionNumber, commit, sha256, manifest.size(),
            completed.versionStatus());
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

    /** 发布结果。status 是版本的最终状态。 */
    public record Published(String version, String commit, String sha256, int fileCount,
                             String status) {}
}
