package com.skillmesh.api.source;

import com.skillmesh.api.audit.AuditOutcome;
import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.common.Visibility;
import com.skillmesh.api.scan.SkillFrontmatter;
import com.skillmesh.api.license.LicenseDetector;
import com.skillmesh.api.skill.LicenseStatus;
import com.skillmesh.api.skill.SkillService;
import com.skillmesh.api.skill.SlugRules;
import com.skillmesh.api.skill.SourceEntity;
import com.skillmesh.api.skill.SourceRepository;
import com.skillmesh.api.skill.dto.CreateSkillRequest;
import com.skillmesh.api.skill.dto.SkillSourceInput;
import com.skillmesh.api.source.dto.ImportFromGitBatchRequest;
import com.skillmesh.api.source.dto.ImportFromGitBatchResponse;
import com.skillmesh.api.source.dto.ImportFromGitRequest;
import com.skillmesh.api.source.dto.ImportFromGitResponse;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final GitHostPolicy policy;
    /** 每条一个独立事务：一条失败不该把已经导进来的也撤回去。 */
    private final TransactionTemplate transactions;

    public GitSourceImportService(SourceService sources, GitSourceUpdateService updates,
                                  GitArtifactPublisher publisher, SkillService skillService,
                                  SourceRepository sourceRepository, AuditService audit,
                                  GitHostPolicy policy,
                                  PlatformTransactionManager transactionManager) {
        this.sources = sources;
        this.updates = updates;
        this.publisher = publisher;
        this.skillService = skillService;
        this.sourceRepository = sourceRepository;
        this.audit = audit;
        this.policy = policy;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 过主机闸门，被拦下就留一笔安全审计（§4.3 GIT-002）。
     *
     * 拦下的是安全事件，不是普通的参数错误：有人在拿非白名单主机或内网地址
     * 试服务端，这一笔要留下来查。审计里只记主机名——仓库地址可能带着凭据。
     */
    private void requireAllowedHost(CurrentUser user, String repositoryUrl) {
        try {
            policy.requireAllowed(repositoryUrl);
        } catch (ApiException e) {
            audit.record(user, "security.git_blocked", "repository", hostOf(repositoryUrl),
                AuditOutcome.failure, Map.of("reason", e.getMessage()));
            throw e;
        }
    }

    /** 只留主机名。仓库地址里可能内嵌凭据，进审计流水前先摘掉。 */
    private static String hostOf(String repositoryUrl) {
        try {
            String host = URI.create(repositoryUrl == null ? "" : repositoryUrl.trim()).getHost();
            return host == null || host.isBlank() ? "unknown" : host;
        } catch (IllegalArgumentException e) {
            return "unknown";
        }
    }

    /**
     * 导入。
     *
     * 先拉仓库、定位目录，确认那儿真的有 Skill，再建 Skill——
     * 反过来的话，路径写错会留下一个没有内容的空 Skill。
     */
    @Transactional
    public ImportFromGitResponse importSkill(CurrentUser user, ImportFromGitRequest req) {
        requireAllowedHost(user, req.repositoryUrl());
        try (SourceService.Checkout checkout =
                 sources.checkout(req.repositoryUrl(), req.ref())) {
            Path skillDir = updates.locate(checkout.root(), req.path());
            Map<?, ?> meta = SkillFrontmatter.read(skillDir);

            String license = detectLicense(skillDir, checkout.root());
            var created = skillService.create(user, new CreateSkillRequest(
                req.slug(), req.name(), req.description(), req.visibility(),
                null, null, mergeTags(req.tags(), SkillFrontmatter.tags(meta)),
                SkillFrontmatter.extension(meta),
                new SkillSourceInput("git", providerOf(req.repositoryUrl()),
                    req.repositoryUrl(), req.path(),
                    sources.refType(checkout.root(), req.ref()), req.ref(),
                    checkout.commit(), license,
                    license == null ? LicenseStatus.unknown : LicenseStatus.detected)));

            SourceEntity source = sourceRepository
                .findBySkillIdOrderByCreatedAtDesc(created.id()).get(0);

            GitArtifactPublisher.Published published = publisher.publish(user, created.id(),
                source.getId(), checkout.commit(), skillDir, "1.0.0",
                "从 Git 导入：" + req.repositoryUrl() + "@"
                    + shortCommit(checkout.commit()));

            audit.record(user, "skill.import_from_git", "skill", created.id(),
                AuditOutcome.success,
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

    /**
     * 一次导入多个。
     *
     * 给的是 Hermes 这类生态的仓库：一个仓库几十个 skill，一个一个导太慢。
     * 名字与描述取 SKILL.md 的 frontmatter——那是 skill 自己声明的，比猜的准；
     * slug 只能从目录名推，推不出来或撞名的那条单独算失败，不影响别的。
     *
     * 逐条独立：每条在自己的事务里，一条失败不牵连已经导进来的。
     */
    public ImportFromGitBatchResponse importBatch(CurrentUser user,
                                                   ImportFromGitBatchRequest req) {
        // 批量导入建不出团队或组织可见的 Skill：请求里没有组织与团队这两个字段。
        // 以前是请求上的 @Pattern 挡着的，现在可见性是枚举，闸门挪到这里。
        if (req.visibility() != Visibility.PRIVATE && req.visibility() != Visibility.PUBLIC) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "批量导入的可见性只能是 private 或 public");
        }
        requireAllowedHost(user, req.repositoryUrl());
        List<ImportFromGitBatchResponse.Imported> imported = new ArrayList<>();
        List<ImportFromGitBatchResponse.Failed> failed = new ArrayList<>();
        try (SourceService.Checkout checkout =
                 sources.checkout(req.repositoryUrl(), req.ref())) {
            for (String path : req.paths()) {
                try {
                    imported.add(transactions.execute(status ->
                        importOne(user, req, checkout, path)));
                } catch (ApiException e) {
                    failed.add(new ImportFromGitBatchResponse.Failed(path, e.getMessage()));
                } catch (RuntimeException e) {
                    failed.add(new ImportFromGitBatchResponse.Failed(path,
                        "导入失败：" + e.getMessage()));
                }
            }
        }
        return new ImportFromGitBatchResponse(imported, failed);
    }

    /**
     * 认出这份 Skill 的许可证。
     *
     * 许可证通常在仓库根上，不在 Skill 目录里：两处都找。
     * 认出来只是「这份内容看起来是哪一份」，能不能再分发仍然要人判断。
     */
    private String detectLicense(Path skillDir, Path checkoutRoot) {
        String detected = LicenseDetector.detectInDirectory(skillDir);
        if (detected == null) {
            detected = LicenseDetector.detectInDirectory(checkoutRoot);
        }
        return detected;
    }

    /** 导一个。名字与描述取 frontmatter，slug 从目录名推。 */
    private ImportFromGitBatchResponse.Imported importOne(CurrentUser user,
                                                           ImportFromGitBatchRequest req,
                                                           SourceService.Checkout checkout,
                                                           String path) {
        Path skillDir = updates.locate(checkout.root(), path);
        Map<?, ?> meta = SkillFrontmatter.read(skillDir);
        String name = SkillFrontmatter.text(meta, "name");
        String description = SkillFrontmatter.text(meta, "description");
        String dirName = skillDir.getFileName().toString();
        if (name.isEmpty()) {
            name = dirName;
        }
        if (description.isEmpty()) {
            description = name;
        }
        String slug = SlugRules.fromName(dirName);

        String license = detectLicense(skillDir, checkout.root());
        var created = skillService.create(user, new CreateSkillRequest(
            slug, name, description, req.visibility(),
            null, null, mergeTags(req.tags(), SkillFrontmatter.tags(meta)),
            SkillFrontmatter.extension(meta),
            new SkillSourceInput("git", providerOf(req.repositoryUrl()),
                req.repositoryUrl(), path,
                sources.refType(checkout.root(), req.ref()), req.ref(),
                checkout.commit(), license,
                license == null ? LicenseStatus.unknown : LicenseStatus.detected)));

        SourceEntity source = sourceRepository
            .findBySkillIdOrderByCreatedAtDesc(created.id()).get(0);

        GitArtifactPublisher.Published published = publisher.publish(user, created.id(),
            source.getId(), checkout.commit(), skillDir, "1.0.0",
            "从 Git 导入：" + req.repositoryUrl() + "@" + shortCommit(checkout.commit()));

        audit.record(user, "skill.import_from_git", "skill", created.id(),
            AuditOutcome.success,
            Map.of("slug", created.slug(), "version", published.version(),
                "commit", published.commit(), "status", published.status()));

        return new ImportFromGitBatchResponse.Imported(path, created.id().toString(),
            created.slug(), published.version(), published.commit(), published.status());
    }

    /**
     * 合并标签：请求里给的在前，frontmatter 里的补在后面。
     *
     * 重复的只留一个。服务端最多收 30 个，超了请求校验会挡下整个导入。
     */
    private List<String> mergeTags(List<String> requested, List<String> fromFrontmatter) {
        List<String> merged = new ArrayList<>();
        for (String tag : concat(requested, fromFrontmatter)) {
            String trimmed = tag == null ? "" : tag.trim();
            if (trimmed.isEmpty() || merged.contains(trimmed)) {
                continue;
            }
            merged.add(trimmed);
            if (merged.size() == 30) {
                break;
            }
        }
        return merged;
    }

    private List<String> concat(List<String> left, List<String> right) {
        List<String> all = new ArrayList<>();
        if (left != null) {
            all.addAll(left);
        }
        if (right != null) {
            all.addAll(right);
        }
        return all;
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
