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

    public VersionEditService(SkillRepository skills, SkillService skillService,
                              SkillVersionRepository versions, SkillVersionService versionService,
                              ArtifactRepository artifacts, ArtifactStorage storage,
                              UploadSessionRepository uploads, UploadService uploadService) {
        this.skills = skills;
        this.skillService = skillService;
        this.versions = versions;
        this.versionService = versionService;
        this.artifacts = artifacts;
        this.storage = storage;
        this.uploads = uploads;
        this.uploadService = uploadService;
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
        ArtifactEntity sourceArtifact = artifactOf(source);

        String path = normalizePath(request.path());
        String operation = request.operation() == null ? "replace" : request.operation();
        boolean delete = "delete".equals(operation);

        byte[] content = null;
        if (!delete) {
            content = request.content() == null
                ? new byte[0] : request.content().getBytes(StandardCharsets.UTF_8);
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
            toManifest(manifest)));

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
        String latest = null;
        for (SkillVersionEntity version : versions.findBySkillIdOrderByCreatedAtDesc(skillId)) {
            if (latest == null || VersionOrder.isGreater(version.getVersion(), latest)) {
                latest = version.getVersion();
            }
        }
        return latest == null ? "1.0.0" : bumpPatch(latest);
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
