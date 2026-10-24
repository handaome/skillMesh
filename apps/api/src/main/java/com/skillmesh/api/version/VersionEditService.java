package com.skillmesh.api.version;

import com.skillmesh.api.artifact.ArtifactEntity;
import com.skillmesh.api.artifact.ArtifactRepository;
import com.skillmesh.api.artifact.ArtifactService;
import com.skillmesh.api.artifact.ArtifactStorage;
import com.skillmesh.api.artifact.ZipManifest;
import com.skillmesh.api.artifact.ZipRewriter;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.recycle.RecycleService;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SkillService;
import com.skillmesh.api.upload.UploadService;
import com.skillmesh.api.upload.UploadSessionRepository;
import com.skillmesh.api.version.dto.CreateVersionRequest;
import com.skillmesh.api.version.dto.EditFileRequest;
import com.skillmesh.api.version.dto.EditFileResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 编辑已发布版本里的一个文件，产出**新版本**。
 *
 * 版本不可变（设计文档 §2：「禁止静默覆盖已发布版本；修复通过新版本完成」），
 * 所以「编辑」不是就地改，而是基于源版本重打包出一个新版本。
 *
 * 新版本走的是**和 CLI 发布完全一样**的流程：建候选版本 → 算清单 →
 * 服务端扫描 → 个人与公开直接发布，团队与组织进待审队列。
 * 绕开扫描那道闸，网页端就成了发布链路的旁路——所以这里复用
 * {@link SkillVersionService#create} 与 {@link UploadService#complete}，
 * 不另写一套发布逻辑。
 */
@Service
public class VersionEditService {

    /**
     * 一次编辑最多改这么多字节的文件。
     *
     * 1MB：真实的 Skill 文件最大也就几十 KB（整个 Skill 常见的量级是百 KB），
     * 1MB 已经很宽裕。真正的天花板是请求体通道（Tomcat 默认只收 2MB 的 POST），
     * 那个限制改不动也不该改——文本内容走 JSON 请求体，1MB 够用了。
     * 真要传大文件，那是制品上传的活（直传对象存储），不走这条路。
     */
    public static final int MAX_FILE_BYTES = 1 << 20;

    private final SkillRepository skills;
    private final SkillService skillService;
    private final SkillVersionRepository versions;
    private final SkillVersionService versionService;
    private final ArtifactRepository artifacts;
    private final ArtifactStorage storage;
    private final UploadSessionRepository uploads;
    private final UploadService uploadService;
    private final RecycleService recycle;

    public VersionEditService(SkillRepository skills, SkillService skillService,
                              SkillVersionRepository versions, SkillVersionService versionService,
                              ArtifactRepository artifacts, ArtifactStorage storage,
                              UploadSessionRepository uploads, UploadService uploadService,
                              RecycleService recycle) {
        this.skills = skills;
        this.skillService = skillService;
        this.versions = versions;
        this.versionService = versionService;
        this.artifacts = artifacts;
        this.storage = storage;
        this.uploads = uploads;
        this.uploadService = uploadService;
        this.recycle = recycle;
    }

    /**
     * 改一个文件，产出新版本。
     *
     * 权限与发版一致：private/public 只有属主能发，团队与组织要 publisher 及以上。
     * 编辑就是发版的一部分，门槛不能比发版松。
     */
    @Transactional
    public EditFileResponse edit(CurrentUser user, UUID skillId, String sourceVersion,
                                  EditFileRequest request) {
        SkillEntity skill = skills.findById(skillId)
            .orElseThrow(() -> ApiException.notFound("Skill"));
        if (!skillService.canView(user, skill)) {
            throw ApiException.notFound("Skill");
        }
        skillService.requirePublishRight(user, skill);

        SkillVersionEntity source = versions.findBySkillIdAndVersion(skillId, sourceVersion)
            .orElseThrow(() -> ApiException.notFound("版本 " + sourceVersion));
        // 编辑要基于不旧于最新已发布版本的那一版。
        //
        // 拿旧底稿重打包，产出的新版本号是新的，内容却是旧的——
        // 新版本里的改动就这么被悄悄盖掉了。宁可在这里说清楚。
        String latestPublished = latestPublishedVersion(skillId);
        if (latestPublished != null && VersionOrder.isGreater(latestPublished, sourceVersion)) {
            throw new ApiException(409, ErrorCodes.VERSION_CONFLICT,
                "这个版本不是最新的（最新已发布的是 " + latestPublished + "），"
                    + "基于最新版本编辑才不会丢改动");
        }
        ArtifactEntity sourceArtifact = artifactOf(source);

        String path = normalizePath(request.path());
        String operation = request.operation() == null ? "replace" : request.operation();
        boolean delete = "delete".equals(operation);

        byte[] content = null;
        if (!delete) {
            content = decodeContent(request);
            if (content.length > MAX_FILE_BYTES) {
                throw new ApiException(413, ErrorCodes.VALIDATION_FAILED,
                    "文件超过 " + (MAX_FILE_BYTES >> 10) + " KB 上限");
            }
        }

        // 重打包：换掉/新增/删除这一个 entry，其余原样带过去（含它们的权限位）。
        byte[] original = readArtifact(sourceArtifact);
        boolean executable = request.executable() == null
            ? ZipRewriter.isExecutable(original, path)
            : request.executable();
        byte[] repacked = delete
            ? ZipRewriter.apply(original, List.of(new ZipRewriter.Change(path, null, false)))
            : ZipRewriter.replace(original, path, content, executable);

        // 清单与摘要由服务端算——不是发布方自填的那份。
        List<Map<String, Object>> manifest = ZipManifest.of(repacked);
        if (manifest.isEmpty()) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "改完之后这个 Skill 里一个文件都没有了");
        }
        String sha256 = ArtifactService.sha256(repacked);
        String version = nextVersion(skillId, request.version());

        // 走正常发版：建候选版本 + 上传会话。complete() 再去做扫描、
        // 判定发布还是送审——那道闸不能绕。
        var created = versionService.create(user, skillId, new CreateVersionRequest(
            version, request.channel() == null ? "stable" : request.channel(),
            null, null, request.changelog() == null ? "" : request.changelog(),
            repacked.length, manifest.size(), sha256,
            toManifest(manifest),
            // 编辑是服务端自己基于某个已发布版本改的，没有客户端带来的基线。
            null));

        // 内容在服务端生成，没有客户端要传的字节：直接写进临时位置，
        // 再走 complete 转正。
        var session = uploads.findById(created.upload().sessionId())
            .orElseThrow(() -> ApiException.notFound("上传会话"));
        storage.put(session.getTemporaryStorageKey(), repacked, "application/zip");

        var completed = uploadService.complete(user, session.getId());
        return new EditFileResponse(skillId, version, manifest.size(), sha256,
            completed.versionStatus());
    }

    /**
     * 把回收站里的一个文件放回当前已发布版本，产出新版本。
     *
     * 回收站恢复走这条路：被删的文件内容还在它最后出现的那一版里
     * （版本不可变，制品不会被删），把它取出来放回去就恢复了。
     *
     * 别的文件保持现状——恢复的是那一个文件，不是回滚到旧版。
     */
    @Transactional
    public EditFileResponse restore(CurrentUser user, UUID skillId, String path) {
        var recycled = recycle.require(user, skillId, path);
        SkillEntity skill = skills.findById(skillId)
            .orElseThrow(() -> ApiException.notFound("Skill"));
        if (!skillService.canView(user, skill)) {
            throw ApiException.notFound("Skill");
        }
        skillService.requirePublishRight(user, skill);

        SkillVersionEntity source = versions.findBySkillIdAndVersion(skillId,
                recycled.sourceVersion())
            .orElseThrow(() -> ApiException.notFound("版本 " + recycled.sourceVersion()));
        ArtifactEntity sourceArtifact = artifactOf(source);
        String normalized = normalizePath(path);

        // 内容从源版本里取。
        byte[] sourceZip = readArtifact(sourceArtifact);
        byte[] content = readEntry(sourceZip, normalized);
        if (content == null) {
            throw new ApiException(404, ErrorCodes.NOT_FOUND,
                "版本 " + recycled.sourceVersion() + " 里没有这个文件：" + path);
        }
        boolean executable = ZipRewriter.isExecutable(sourceZip, normalized);

        // 底稿用当前最新已发布版本：恢复的是那一个文件，不是回滚。
        SkillVersionEntity latest = latestPublished(skillId);
        if (latest == null) {
            throw new ApiException(409, ErrorCodes.VERSION_CONFLICT,
                "这个 Skill 还没有已发布版本，没法恢复");
        }
        byte[] repacked = ZipRewriter.replace(readArtifact(artifactOf(latest)),
            normalized, content, executable);

        List<Map<String, Object>> manifest = ZipManifest.of(repacked);
        String sha256 = ArtifactService.sha256(repacked);
        String version = nextVersion(skillId, null);

        var created = versionService.create(user, skillId, new CreateVersionRequest(
            version, "stable", null, null, "恢复文件 " + path,
            repacked.length, manifest.size(), sha256, toManifest(manifest), null));
        var session = uploads.findById(created.upload().sessionId())
            .orElseThrow(() -> ApiException.notFound("上传会话"));
        storage.put(session.getTemporaryStorageKey(), repacked, "application/zip");
        var completed = uploadService.complete(user, session.getId());
        // 恢复过了就从回收站里去掉：文件回到版本里了，再恢复一次是多余的。
        recycle.markRestored(skillId, recycled.path());
        return new EditFileResponse(skillId, version, manifest.size(), sha256,
            completed.versionStatus());
    }

    /** 最新已发布版本。 */
    private SkillVersionEntity latestPublished(UUID skillId) {
        for (SkillVersionEntity row : versions.findBySkillIdOrderByCreatedAtDescThenId(skillId)) {
            if ("published".equals(row.getStatus()) && row.getArtifactId() != null) {
                return row;
            }
        }
        return null;
    }

    /** 从 zip 字节里取一个 entry 的内容。取不到返回 null。 */
    private byte[] readEntry(byte[] zip, String path) {
        try (java.util.zip.ZipInputStream reader = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = reader.getNextEntry()) != null) {
                if (entry.isDirectory() || !path.equals(entry.getName())) {
                    continue;
                }
                return reader.readAllBytes();
            }
        } catch (java.io.IOException e) {
            return null;
        }
        return null;
    }

    /**
     * 文件内容。
     *
     * 文本走 {@code content}，二进制走 {@code content_base64}——图片、字体这些
     * 塞不进 UTF-8 文本，上传时由前端编码成 base64。
     */
    private byte[] decodeContent(EditFileRequest request) {
        String base64 = request.contentBase64();
        if (base64 != null && !base64.isBlank()) {
            try {
                return Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException e) {
                throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                    "content_base64 不是合法的 base64");
            }
        }
        return request.content() == null
            ? new byte[0] : request.content().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 路径规整：去掉开头的斜杠，拒绝穿越。
     *
     * 路径会进 zip 的 entry 名，而安装器把 entry 名当成目标目录下的相对路径——
     * 放进 ".." 就是往目标目录外写文件。
     */
    private String normalizePath(String raw) {
        String path = raw == null ? "" : raw.trim().replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isBlank()) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "文件路径不能为空");
        }
        if (path.contains("..")) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "文件路径不能包含 ..");
        }
        return path;
    }

    /**
     * 新版本号。
     *
     * 客户端不给就按已有的最大版本号加一个 patch——与 CLI 的规则一致
     * （CLI 的 nextVersion）。
     */
    private String nextVersion(UUID skillId, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        String latest = latestVersion(skillId);
        return latest == null ? "1.0.0" : bumpPatch(latest);
    }

    /** 这个 Skill 已有的最大版本号（按版本号比，不按创建时间）。 */
    private String latestVersion(UUID skillId) {
        String latest = null;
        for (SkillVersionEntity version : versions.findBySkillIdOrderByCreatedAtDesc(skillId)) {
            if (latest == null || VersionOrder.isGreater(version.getVersion(), latest)) {
                latest = version.getVersion();
            }
        }
        return latest;
    }

    /**
     * 最新已发布版本的版本号。没有已发布版本时返回 null。
     *
     * 草稿、被拒的版本不算：它们的内容没进过世界，编辑基于已发布的那版不算丢东西。
     */
    private String latestPublishedVersion(UUID skillId) {
        String latest = null;
        for (SkillVersionEntity version : versions.findBySkillIdOrderByCreatedAtDesc(skillId)) {
            if (!"published".equals(version.getStatus())) {
                continue;
            }
            if (latest == null || VersionOrder.isGreater(version.getVersion(), latest)) {
                latest = version.getVersion();
            }
        }
        return latest;
    }

    /** 给版本号的 patch 位加一。解析不了就报错——别猜。 */
    private String bumpPatch(String version) {
        String[] parts = version.split("\\.");
        if (parts.length != 3) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "版本号 " + version + " 不是 x.y.z 形式，请自己指定新版本号");
        }
        try {
            int patch = Integer.parseInt(parts[2]);
            return parts[0] + "." + parts[1] + "." + (patch + 1);
        } catch (NumberFormatException e) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "版本号 " + version + " 的 patch 位不是数字，请自己指定新版本号");
        }
    }

    private List<CreateVersionRequest.ManifestEntry> toManifest(List<Map<String, Object>> manifest) {
        List<CreateVersionRequest.ManifestEntry> entries = new ArrayList<>();
        for (Map<String, Object> item : manifest) {
            entries.add(new CreateVersionRequest.ManifestEntry(
                String.valueOf(item.get("path")),
                String.valueOf(item.get("sha256")),
                ((Number) item.getOrDefault("size_bytes", 0)).longValue(),
                Boolean.TRUE.equals(item.get("executable"))));
        }
        return entries;
    }

    private ArtifactEntity artifactOf(SkillVersionEntity version) {
        if (version.getArtifactId() == null) {
            throw ApiException.notFound("版本 " + version.getVersion() + " 的制品");
        }
        return artifacts.findById(version.getArtifactId())
            .orElseThrow(() -> ApiException.notFound("制品"));
    }

    private byte[] readArtifact(ArtifactEntity artifact) {
        try (InputStream in = storage.get(artifact.getStorageKey())) {
            return in.readAllBytes();
        } catch (IOException | IllegalStateException e) {
            throw new ApiException(502, ErrorCodes.ARTIFACT_MISSING,
                "读取制品失败：" + e.getMessage());
        }
    }
}
