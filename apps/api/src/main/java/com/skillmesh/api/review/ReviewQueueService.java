package com.skillmesh.api.review;

import com.skillmesh.api.artifact.ArtifactEntity;
import com.skillmesh.api.artifact.ArtifactRepository;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.UserEntity;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
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
        List<SkillVersionEntity> rows = versions.findReviewable(user.id(), wanted, REVIEWER_ROLES,
                PageRequest.of(0, capped));
        // 一页里的技能、扫描、来源、人各查一次：逐条查就是 4N 次往返，
        // 本机到远程库一次往返几十毫秒，一页 50 条就是 200 次。
        Lookups lookups = lookupsOf(rows);
        return rows.stream().map(version -> toItem(version, lookups)).toList();
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
        Lookups lookups = lookupsOf(List.of(entity));
        List<Map<String, Object>> findings = findingsOf(entity, lookups);
        return new ReviewDetailResponse(toItem(entity, lookups),
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
     *
     * 团队范围才看团队角色：组织范围的版本只有组织角色这一层，
     * 别让一个挂在组织可见 Skill 上的 owner_team_id 把审核作用域带偏。
     */
    private boolean canReview(CurrentUser user, SkillVersionEntity version) {
        SkillEntity skill = skills.findById(version.getSkillId()).orElse(null);
        if (skill == null) {
            return false;
        }
        UUID scopeTeamId = "team".equals(skill.getVisibility()) ? skill.getOwnerTeamId() : null;
        String role = orgs.roleIn(skill.getOrganizationId(), scopeTeamId, user.id());
        return OrgService.roleAtLeast(role, "reviewer");
    }

    // ponytail: 逐条查 skills/scans/sources/users（N+1），一页 50 条就是 200 次查询。
    // 已改：整页的关联数据一次批量查齐（见 lookupsOf），逐条拼装时只查表不查库。
    private PendingReviewItem toItem(SkillVersionEntity version, Lookups lookups) {
        SkillEntity skill = lookups.skills().get(version.getSkillId());
        return new PendingReviewItem(
            version.getSkillId(),
            skill == null ? null : skill.getSlug(),
            skill == null ? null : skill.getName(),
            version.getVersion(),
            version.getChannel(),
            displayName(version.getCreatedBy(), lookups.users()),
            submittedAt(version),
            scanSummary(version, lookups.scans()),
            sourceSummary(version, lookups.sources()),
            version.getStatus());
    }

    /**
     * 一批版本要用的关联数据：一次批量查齐。
     *
     * 逐条查就是每条 4 次往返（技能、扫描、来源、人），一页 50 条 200 次；
     * 本机到远程库一次往返几十毫秒，这个队列页就直接卡住了。
     */
    private Lookups lookupsOf(List<SkillVersionEntity> rows) {
        return new Lookups(
            skills.findAllById(rows.stream().map(SkillVersionEntity::getSkillId).toList())
                .stream().collect(Collectors.toMap(SkillEntity::getId, skill -> skill)),
            scans.findLatestByVersionIds(rows.stream().map(SkillVersionEntity::getId).toList()),
            sources.findAllById(rows.stream().map(SkillVersionEntity::getSourceId)
                    .filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(SourceEntity::getId, source -> source)),
            users.findAllById(rows.stream().map(SkillVersionEntity::getCreatedBy)
                    .filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(UserEntity::getId, user -> user)));
    }

    /**
     * 队列里的送审时间——进待审队列的那一刻，不是建版本的那一刻
     * （建版本与上传制品可能隔半天，队列要显示的是进队列的时间）。
     * 老数据没有这个时间，退回建版本时间。
     */
    private String submittedAt(SkillVersionEntity version) {
        OffsetDateTime at = version.getSubmittedAt() != null
            ? version.getSubmittedAt() : version.getCreatedAt();
        return at == null ? null : at.toString();
    }

    private ReviewScanSummary scanSummary(SkillVersionEntity version, Map<UUID, ScanEntity> scans) {
        ScanEntity scan = scans.get(version.getId());
        return scan == null ? new ReviewScanSummary("unknown", "未扫描")
            : new ReviewScanSummary(scan.getStatus(), scan.getSummary());
    }

    private ReviewSourceSummary sourceSummary(SkillVersionEntity version,
                                              Map<UUID, SourceEntity> sources) {
        if (version.getSourceId() == null) {
            return new ReviewSourceSummary(null, null, null, version.getSourceCommit());
        }
        SourceEntity source = sources.get(version.getSourceId());
        return source == null
            ? new ReviewSourceSummary(null, null, null, version.getSourceCommit())
            : new ReviewSourceSummary(source.getProvider(), source.getRepositoryUrl(),
                source.getRefValue(), version.getSourceCommit());
    }

    private List<Map<String, Object>> findingsOf(SkillVersionEntity version, Lookups lookups) {
        ScanEntity scan = lookups.scans().get(version.getId());
        return scan == null ? List.of() : scan.getFindingsJson();
    }

    private String displayName(UUID userId, Map<UUID, UserEntity> users) {
        if (userId == null) {
            return null;
        }
        UserEntity user = users.get(userId);
        return user == null ? null : user.getDisplayName();
    }

    private ArtifactEntity artifactOf(SkillVersionEntity version) {
        if (version.getArtifactId() == null) {
            return null;
        }
        return artifacts.findById(version.getArtifactId()).orElse(null);
    }

    /** 一批版本要用的关联数据：批量查齐的结果，逐条拼装时只查表不查库。 */
    private record Lookups(Map<UUID, SkillEntity> skills,
                           Map<UUID, ScanEntity> scans,
                           Map<UUID, SourceEntity> sources,
                           Map<UUID, UserEntity> users) {}
}
