package com.skillmesh.api.review;

import com.skillmesh.api.artifact.ArtifactEntity;
import com.skillmesh.api.artifact.ArtifactRepository;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.UserRepository;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.diff.ArtifactDiffService;
import com.skillmesh.api.org.OrgService;
import com.skillmesh.api.org.Roles;
import com.skillmesh.api.review.dto.PendingReviewItem;
import com.skillmesh.api.review.dto.ReviewDetailResponse;
import com.skillmesh.api.review.dto.ReviewScanSummary;
import com.skillmesh.api.review.dto.ReviewSourceSummary;
import com.skillmesh.api.scan.ScanEntity;
import com.skillmesh.api.scan.ScanRepository;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SourceEntity;
import com.skillmesh.api.skill.SourceRepository;
import com.skillmesh.api.version.SkillVersionEntity;
import com.skillmesh.api.version.SkillVersionRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审核队列（设计文档 §5.3）。
 *
 * 只列「我审得了」的：技能的归属范围里我是 reviewer 及以上。
 * 可见性在 SQL 里判（先过滤、再排序、最后取 limit）——
 * 反过来做会漏数据：排在窗口外的待审版本永远看不到。
 */
@Service
public class ReviewQueueService {

    /**
     * 能审的角色：reviewer 及以上。
     *
     * 词表在 Roles，这里只是把它拼成 SQL 能用的形式——
     * 角色等级比较（roleAtLeast）在 Java 侧，SQL 只判「属于这一档」。
     */
    private static final String REVIEWER_ROLES = String.join(",",
        Roles.ORG_RANK.subList(0, Roles.ORG_RANK.indexOf("reviewer") + 1));

    private final SkillVersionRepository versions;
    private final SkillRepository skills;
    private final SourceRepository sources;
    private final ScanRepository scans;
    private final ArtifactRepository artifacts;
    private final OrgService orgs;
    private final UserRepository users;
    private final ArtifactDiffService diffs;

    public ReviewQueueService(SkillVersionRepository versions, SkillRepository skills,
                              SourceRepository sources, ScanRepository scans,
                              ArtifactRepository artifacts, OrgService orgs,
                              UserRepository users, ArtifactDiffService diffs) {
        this.versions = versions;
        this.skills = skills;
        this.sources = sources;
        this.scans = scans;
        this.artifacts = artifacts;
        this.orgs = orgs;
        this.users = users;
        this.diffs = diffs;
    }

    /** 待审列表。 */
    @Transactional(readOnly = true)
    public List<PendingReviewItem> pending(CurrentUser user, String status, int limit) {
        String wanted = status == null || status.isBlank() ? "pending_review" : status;
        int capped = Math.clamp(limit, 1, 200);
        return versions.findReviewable(user.id(), wanted, REVIEWER_ROLES,
                PageRequest.of(0, capped)).stream()
            .map(version -> toItem(version))
            .toList();
    }

    /**
     * 审核详情：正文、扫描命中、来源、文件级差异。
     *
     * 事务只包住取数据那几步；下载 zip（正文 + diff）在事务外做。
     * 事务期间占着数据库连接下载对象存储，并发看审核页会把连接池拖垮——
     * Task 5 已经把 {@code ArtifactDiffService} 的两个方法改成 {@code NOT_SUPPORTED}，
     * 这里不能又把下载包回事务里。
     *
     * {@code NOT_SUPPORTED} 挂起调用方的事务——本方法自己没有事务，
     * 每次仓库调用各是独立短事务，下载时线程上没有绑定连接。
     * 与 Task 5 的 {@code ArtifactDiffService} 同一个做法。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReviewDetailResponse detail(CurrentUser user, UUID skillId, String version) {
        SkillVersionEntity entity = versions.findBySkillIdAndVersion(skillId, version)
            .orElseThrow(() -> ApiException.notFound("版本"));
        if (!canReview(user, entity)) {
            throw ApiException.notFound("版本");
        }
        List<Map<String, Object>> findings = findingsOf(entity);
        return new ReviewDetailResponse(toItem(entity),
            diffs.readSkillMarkdown(artifactOf(entity)),
            entity.getChangelog(),
            findings,
            diffs.diffAgainstLatestPublished(skillId, entity));
    }

    /**
     * 我能不能审这个版本。
     *
     * 团队范围的看我在该团队的角色，没有团队成员行就回落到组织角色
     * ——roleIn 的语义，全站统一（ReviewService.requireRole 也用它）。
     */
    private boolean canReview(CurrentUser user, SkillVersionEntity version) {
        SkillEntity skill = skills.findById(version.getSkillId()).orElse(null);
        if (skill == null) {
            return false;
        }
        String role = orgs.roleIn(skill.getOrganizationId(), skill.getOwnerTeamId(), user.id());
        return OrgService.roleAtLeast(role, "reviewer");
    }

    // ponytail: 逐条查 skills/scans/sources/users（N+1），一页 50 条就是 200 次查询。
    // 待审队列量级不大，先这样；量大时改成按 id 批量查再拼。
    private PendingReviewItem toItem(SkillVersionEntity version) {
        SkillEntity skill = skills.findById(version.getSkillId()).orElse(null);
        return new PendingReviewItem(
            version.getSkillId(),
            skill == null ? null : skill.getSlug(),
            skill == null ? null : skill.getName(),
            version.getVersion(),
            version.getChannel(),
            displayName(version.getCreatedBy()),
            version.getCreatedAt() == null ? null : version.getCreatedAt().toString(),
            scanSummary(version),
            sourceSummary(version));
    }

    private ReviewScanSummary scanSummary(SkillVersionEntity version) {
        return scans.findFirstBySkillVersionIdOrderByStartedAtDesc(version.getId())
            .map(scan -> new ReviewScanSummary(scan.getStatus(), scan.getSummary()))
            .orElse(new ReviewScanSummary("unknown", "未扫描"));
    }

    private ReviewSourceSummary sourceSummary(SkillVersionEntity version) {
        if (version.getSourceId() == null) {
            return new ReviewSourceSummary(null, null, null, version.getSourceCommit());
        }
        return sources.findById(version.getSourceId())
            .map(source -> new ReviewSourceSummary(source.getProvider(),
                source.getRepositoryUrl(), source.getRefValue(), version.getSourceCommit()))
            .orElse(new ReviewSourceSummary(null, null, null, version.getSourceCommit()));
    }

    private List<Map<String, Object>> findingsOf(SkillVersionEntity version) {
        return scans.findFirstBySkillVersionIdOrderByStartedAtDesc(version.getId())
            .map(ScanEntity::getFindingsJson)
            .orElse(List.of());
    }

    private String displayName(UUID userId) {
        if (userId == null) {
            return null;
        }
        return users.findById(userId).map(user -> user.getDisplayName()).orElse(null);
    }

    private ArtifactEntity artifactOf(SkillVersionEntity version) {
        if (version.getArtifactId() == null) {
            return null;
        }
        return artifacts.findById(version.getArtifactId()).orElse(null);
    }
}
