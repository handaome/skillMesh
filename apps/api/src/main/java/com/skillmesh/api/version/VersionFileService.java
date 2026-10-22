package com.skillmesh.api.version;

import com.skillmesh.api.artifact.ArtifactEntity;
import com.skillmesh.api.artifact.ArtifactFileService;
import com.skillmesh.api.artifact.ArtifactRepository;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SkillService;
import com.skillmesh.api.version.dto.VersionFileContentResponse;
import com.skillmesh.api.version.dto.VersionFilesResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在线浏览 Skill 版本里的文件。
 *
 * 权限跟版本列表走同一道门：{@link SkillService#canView}。能看见这个 Skill
 * 才能看它的文件——文件内容不比元数据更该藏。
 */
@Service
public class VersionFileService {

    private final SkillRepository skills;
    private final SkillService skillService;
    private final SkillVersionRepository versions;
    private final ArtifactRepository artifacts;
    private final ArtifactFileService files;

    public VersionFileService(SkillRepository skills, SkillService skillService,
                              SkillVersionRepository versions, ArtifactRepository artifacts,
                              ArtifactFileService files) {
        this.skills = skills;
        this.skillService = skillService;
        this.versions = versions;
        this.artifacts = artifacts;
        this.files = files;
    }

    /** 某版本的文件清单。 */
    @Transactional(readOnly = true)
    public VersionFilesResponse list(CurrentUser user, UUID skillId, String version) {
        ArtifactEntity artifact = artifactOf(user, skillId, version);
        List<VersionFilesResponse.FileEntry> entries = new ArrayList<>();
        for (Map<String, Object> item : files.listFiles(artifact)) {
            entries.add(new VersionFilesResponse.FileEntry(
                String.valueOf(item.get("path")),
                ((Number) item.getOrDefault("size_bytes", 0)).longValue(),
                String.valueOf(item.get("sha256"))));
        }
        return new VersionFilesResponse(version, entries.size(), entries);
    }

    /** 某个文件的正文。 */
    @Transactional(readOnly = true)
    public VersionFileContentResponse read(CurrentUser user, UUID skillId, String version,
                                           String path) {
        ArtifactEntity artifact = artifactOf(user, skillId, version);
        // 清单算一次就够：size 与 sha 都从它里取，别各读一遍 zip。
        List<Map<String, Object>> manifest = files.listFiles(artifact);
        ArtifactFileService.Content content = files.readContent(artifact, manifest, path);
        if (content == null) {
            throw ApiException.notFound("文件 " + path);
        }
        long size = sizeOf(manifest, path);
        String sha = shaOf(manifest, path);
        // 超大文件读不全：不谎称有正文，只报大小。
        if (content.tooLarge()) {
            return new VersionFileContentResponse(version, path, size, sha, true, null);
        }
        byte[] data = content.data();
        if (ArtifactFileService.isBinary(data)) {
            return new VersionFileContentResponse(version, path, size, sha, true, null);
        }
        return new VersionFileContentResponse(version, path, size, sha,
            false, ArtifactFileService.toText(data));
    }

    /**
     * 取版本的制品，顺便把权限过一遍。
     *
     * 看不见 Skill 就 404，不泄露它存不存在；版本不存在或还没上传制品同样 404。
     */
    private ArtifactEntity artifactOf(CurrentUser user, UUID skillId, String version) {
        SkillEntity skill = skills.findById(skillId).orElseThrow(() -> ApiException.notFound("Skill"));
        if (!skillService.canView(user, skill)) {
            throw ApiException.notFound("Skill");
        }
        SkillVersionEntity entity = versions.findBySkillIdAndVersion(skillId, version)
            .orElseThrow(() -> ApiException.notFound("版本"));
        if (entity.getArtifactId() == null) {
            // 版本还没上传制品：没有文件可看。
            throw ApiException.notFound("版本 " + version + " 的制品");
        }
        return artifacts.findById(entity.getArtifactId())
            .orElseThrow(() -> ApiException.notFound("制品"));
    }

    private long sizeOf(List<Map<String, Object>> manifest, String path) {
        for (Map<String, Object> item : manifest) {
            if (path.equals(String.valueOf(item.get("path")))) {
                return ((Number) item.getOrDefault("size_bytes", 0)).longValue();
            }
        }
        return 0;
    }

    private String shaOf(List<Map<String, Object>> manifest, String path) {
        for (Map<String, Object> item : manifest) {
            if (path.equals(String.valueOf(item.get("path")))) {
                return String.valueOf(item.get("sha256"));
            }
        }
        return "";
    }
}
