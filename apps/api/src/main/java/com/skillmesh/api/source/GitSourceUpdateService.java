package com.skillmesh.api.source;

import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.common.SafePath;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillService;
import com.skillmesh.api.skill.SourceEntity;
import com.skillmesh.api.skill.SourceRepository;
import com.skillmesh.api.source.dto.SkillSourceResponse;
import com.skillmesh.api.source.dto.SourceUpdateCheckResponse;
import com.skillmesh.api.source.dto.SourceUpdateResultResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 从 Git 来源拉取新提交，产出**新版本**。
 *
 * 只改云端的内容。本机装的那份得靠 CLI 的 {@code skillmesh update} 拉回去：
 * 服务端看不到任何人的磁盘。
 */
@Service
public class GitSourceUpdateService {

    private final SourceService sources;
    private final SourceRepository sourceRepository;
    private final SkillService skillService;
    private final GitArtifactPublisher publisher;
    private final AuditService audit;

    public GitSourceUpdateService(SourceService sources, SourceRepository sourceRepository,
                                  SkillService skillService, GitArtifactPublisher publisher,
                                  AuditService audit) {
        this.sources = sources;
        this.sourceRepository = sourceRepository;
        this.skillService = skillService;
        this.publisher = publisher;
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
            GitArtifactPublisher.Published published = publisher.publish(user, skillId,
                source.getId(), checkout.commit(), skillDir, null,
                "从 Git 来源更新：" + source.getRepositoryUrl() + "@"
                    + shortCommit(checkout.commit()));

            source.setResolvedCommit(checkout.commit());
            source.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            sourceRepository.save(source);

            audit.record(user, "skill.update_from_git", "skill", skillId, "success",
                Map.of("version", published.version(), "commit", published.commit(),
                    "sha256", published.sha256(), "status", published.status()));

            return new SourceUpdateResultResponse(published.version(), published.commit(),
                published.sha256(), published.fileCount(), published.status());
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
    Path locate(Path root, String subpath) {
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

    private String shortCommit(String commit) {
        return commit == null || commit.length() <= 8 ? commit : commit.substring(0, 8);
    }
}
