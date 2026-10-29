package com.skillmesh.api.source;

import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.skill.SkillService;
import com.skillmesh.api.skill.SourceEntity;
import com.skillmesh.api.skill.SourceRepository;
import com.skillmesh.api.skill.dto.CreateSkillRequest;
import com.skillmesh.api.skill.dto.SkillSourceInput;
import com.skillmesh.api.source.dto.ImportFromGitRequest;
import com.skillmesh.api.source.dto.ImportFromGitResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 从 Git 仓库导入一个 Skill 到云端。
 *
 * 给的是那些「从 GitHub 下载了、还没进 SkillMesh」的手工 Skill：
 * 导入后它在云端有来源记录，之后就能在网页上查更新、拉新版，
 * 也能被团队装到。
 *
 * 流程与 CLI 的 add + publish 合起来是一回事：拉仓库 → 定位 Skill 目录 →
 * 建 Skill 与来源记录 → 产出首个版本。产出走的是和 CLI 发布一样的链路
 * （扫描 → 个人与公开直接发布，团队与组织进待审队列）。
 */
@Service
public class GitSourceImportService {

    private final SourceService sources;
    private final GitSourceUpdateService updates;
    private final GitArtifactPublisher publisher;
    private final SkillService skillService;
    private final SourceRepository sourceRepository;
    private final AuditService audit;

    public GitSourceImportService(SourceService sources, GitSourceUpdateService updates,
                                  GitArtifactPublisher publisher, SkillService skillService,
                                  SourceRepository sourceRepository, AuditService audit) {
        this.sources = sources;
        this.updates = updates;
        this.publisher = publisher;
        this.skillService = skillService;
        this.sourceRepository = sourceRepository;
        this.audit = audit;
    }

    /**
     * 导入。
     *
     * 先拉仓库、定位目录，确认那儿真的有 Skill，再建 Skill——
     * 反过来的话，路径写错会留下一个没有内容的空 Skill。
     */
    @Transactional
    public ImportFromGitResponse importSkill(CurrentUser user, ImportFromGitRequest req) {
        try (SourceService.Checkout checkout =
                 sources.checkout(req.repositoryUrl(), req.ref())) {
            Path skillDir = updates.locate(checkout.root(), req.path());

            var created = skillService.create(user, new CreateSkillRequest(
                req.slug(), req.name(), req.description(), req.visibility(),
                null, null, req.tags(), Map.of(),
                new SkillSourceInput("git", providerOf(req.repositoryUrl()),
                    req.repositoryUrl(), req.path(),
                    sources.refType(checkout.root(), req.ref()), req.ref(),
                    checkout.commit(), null, "detected")));

            SourceEntity source = sourceRepository
                .findBySkillIdOrderByCreatedAtDesc(created.id()).get(0);

            GitArtifactPublisher.Published published = publisher.publish(user, created.id(),
                source.getId(), checkout.commit(), skillDir, "1.0.0",
                "从 Git 导入：" + req.repositoryUrl() + "@"
                    + shortCommit(checkout.commit()));

            audit.record(user, "skill.import_from_git", "skill", created.id(), "success",
                Map.of("slug", created.slug(), "version", published.version(),
                    "commit", published.commit(), "status", published.status()));

            return new ImportFromGitResponse(created.id().toString(), created.slug(),
                published.version(), published.commit(), published.sha256(),
                published.fileCount(), published.status());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, ErrorCodes.INTERNAL,
                "从 Git 导入失败：" + e.getMessage());
        }
    }

    /** 从仓库地址猜出来源类型。猜不出就是 generic_git。 */
    private String providerOf(String url) {
        String lower = url.toLowerCase();
        if (lower.contains("github.com")) {
            return "github";
        }
        if (lower.contains("gitlab.com")) {
            return "gitlab";
        }
        if (lower.contains("gitee.com")) {
            return "gitee";
        }
        return "generic_git";
    }

    /** commit 缩到 8 位，够认就行。 */
    private String shortCommit(String commit) {
        return commit == null || commit.length() <= 8 ? commit : commit.substring(0, 8);
    }
}
