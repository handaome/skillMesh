package com.skillmesh.api.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmesh.api.artifact.ArtifactEntity;
import com.skillmesh.api.artifact.ArtifactRepository;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.UserEntity;
import com.skillmesh.api.auth.UserRepository;
import com.skillmesh.api.org.OrgRepository;
import com.skillmesh.api.org.Roles;
import com.skillmesh.api.org.TeamRepository;
import com.skillmesh.api.review.dto.BatchDecideRequest;
import com.skillmesh.api.scan.ScanEntity;
import com.skillmesh.api.scan.ScanRepository;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.version.SkillVersionEntity;
import com.skillmesh.api.version.SkillVersionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 批量审核：逐条独立。
 *
 * 关键在「一条过了、另一条没过，两边都算数」。挤在一个事务里的话，第一条失败
 * 会把已经放行的也一起撤回来，用户看到的是「一条都没成」，而其实前面已经过了。
 *
 * 测试数据走独立事务提交：批量审核用的也是独立事务，看不到未提交的行；
 * 审计那边要写 actor 外键，同样得看得见用户行。
 */
@SpringBootTest
class ReviewBatchDecideTest {

    @Autowired
    private ReviewService reviews;

    @Autowired
    private SkillRepository skills;

    @Autowired
    private SkillVersionRepository versions;

    @Autowired
    private ArtifactRepository artifacts;

    @Autowired
    private ScanRepository scans;

    @Autowired
    private UserRepository users;

    @Autowired
    private OrgRepository organizations;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 独立事务：造的数据要提交出去，别的独立事务才看得见。 */
    private TransactionTemplate committed;

    private CurrentUser reviewer;
    private CurrentUser publisher;
    private UUID teamId;
    private UUID organizationId;
    private UUID teamSkillId;
    private UUID pendingVersion;
    private UUID artifactId;
    private final List<UUID> userIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        committed = new TransactionTemplate(transactionManager);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        committed.executeWithoutResult(status -> {
            reviewer = newUser("审核人");
            publisher = newUser("发布人");
            organizationId = newOrganization();
            teamId = newTeam(organizationId);
            addMember(organizationId, reviewer, Roles.DEFAULT_ROLE);
            addMember(organizationId, publisher, Roles.DEFAULT_ROLE);
            addTeamMember(teamId, reviewer, "reviewer");
            addTeamMember(teamId, publisher, "publisher");
            teamSkillId = newTeamSkill(organizationId, teamId);
            pendingVersion = newPendingVersion(teamSkillId);
        });
    }

    /** 造的数据是提交出去的，测试完得自己收拾。 */
    @AfterEach
    void tearDown() {
        committed.executeWithoutResult(status -> {
            jdbc.update("delete from audit_events where target_id = ?", teamSkillId.toString());
            jdbc.update("delete from skill_scans where skill_version_id = ?", pendingVersion);
            jdbc.update("update skills set latest_version = null where id = ?", teamSkillId);
            jdbc.update("delete from skill_versions where id = ?", pendingVersion);
            jdbc.update("delete from artifacts where id = ?", artifactId);
            jdbc.update("delete from skills where id = ?", teamSkillId);
            jdbc.update("delete from team_memberships where team_id = ?", teamId);
            jdbc.update("delete from organization_memberships where organization_id = ?", organizationId);
            jdbc.update("delete from teams where id = ?", teamId);
            jdbc.update("delete from organizations where id = ?", organizationId);
            for (UUID id : userIds) {
                jdbc.update("delete from users where id = ?", id);
            }
        });
    }

    @Test
    void 一条过了另一条没过两边都算数() {
        Map<String, Object> result = reviews.batchDecide(reviewer, request(
            new BatchDecideRequest.Item(teamSkillId, "1.0.0"),
            new BatchDecideRequest.Item(UUID.randomUUID(), "9.9.9")));

        assertThat(succeeded(result)).as("失败明细：%s", failed(result)).hasSize(1);
        assertThat(failed(result)).hasSize(1);
        // 过的那条真的发布出去了，不是只报个成功。
        assertThat(versions.findById(pendingVersion).orElseThrow().getStatus()).isEqualTo("published");
        // 没过的那条带原因：不是「失败了」，是「哪一条、为什么」。
        assertThat(failed(result).get(0)).containsKeys("skill_id", "version", "code", "message");
    }

    @Test
    void 审核自己发布的版本仍然被拒() {
        // 让版本变成 publisher 发的，再让他审自己的。
        committed.executeWithoutResult(status -> {
            SkillVersionEntity version = versions.findById(pendingVersion).orElseThrow();
            version.setCreatedBy(publisher.id());
            versions.save(version);
        });

        Map<String, Object> result = reviews.batchDecide(publisher, request(
            new BatchDecideRequest.Item(teamSkillId, "1.0.0")));

        assertThat(succeeded(result)).as("失败明细：%s", failed(result)).isEmpty();
        assertThat(failed(result)).hasSize(1);
    }

    private BatchDecideRequest request(BatchDecideRequest.Item... items) {
        return new BatchDecideRequest("approve", List.of(items), "");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> succeeded(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("succeeded");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> failed(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("failed");
    }

    private CurrentUser newUser(String name) {
        UUID id = UUID.randomUUID();
        userIds.add(id);
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setOidcIssuer("https://probe.local");
        user.setOidcSubject("probe-" + id);
        user.setDisplayName(name);
        user.setStatus("active");
        user.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        user.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        users.saveAndFlush(user);
        return new CurrentUser(id, user.getOidcSubject(), name, null, "active");
    }

    private UUID newOrganization() {
        UUID id = UUID.randomUUID();
        com.skillmesh.api.org.OrganizationEntity org = new com.skillmesh.api.org.OrganizationEntity();
        org.setId(id);
        org.setSlug("probe-org-" + id);
        org.setName("探针组织");
        org.setStatus("active");
        org.setPolicyJson(Map.of());
        org.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        org.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        organizations.saveAndFlush(org);
        return id;
    }

    private UUID newTeam(UUID organization) {
        UUID id = UUID.randomUUID();
        com.skillmesh.api.org.TeamEntity team = new com.skillmesh.api.org.TeamEntity();
        team.setId(id);
        team.setOrganizationId(organization);
        team.setSlug("probe-team-" + id);
        team.setName("探针团队");
        team.setDescription("测试用");
        team.setStatus("active");
        team.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        team.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        teams.saveAndFlush(team);
        return id;
    }

    /** 插成员关系：直接写 SQL，成员实体是包内私有的。 */
    private void addMember(UUID organization, CurrentUser user, String role) {
        jdbc.update("insert into organization_memberships (organization_id, user_id, role, joined_at) "
            + "values (?, ?, ?, now())", organization, user.id(), role);
    }

    private void addTeamMember(UUID team, CurrentUser user, String role) {
        jdbc.update("insert into team_memberships (team_id, user_id, role, joined_at) "
            + "values (?, ?, ?, now())", team, user.id(), role);
    }

    private UUID newTeamSkill(UUID organization, UUID team) {
        SkillEntity entity = new SkillEntity();
        entity.setId(UUID.randomUUID());
        entity.setSlug("probe-skill-" + UUID.randomUUID());
        entity.setName("探针技能");
        entity.setDescription("测试用");
        entity.setOwnerUserId(publisher.id());
        entity.setOrganizationId(organization);
        entity.setOwnerTeamId(team);
        entity.setVisibility("team");
        entity.setStatus("active");
        entity.setTags(new String[0]);
        entity.setMetadataJson(Map.of());
        entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return skills.saveAndFlush(entity).getId();
    }

    /** 一个待审版本：带制品、扫描通过。 */
    private UUID newPendingVersion(UUID skillId) {
        ArtifactEntity artifact = new ArtifactEntity();
        artifact.setId(UUID.randomUUID());
        artifactId = artifact.getId();
        artifact.setSha256(UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", ""));
        artifact.setStorageKey("probe/" + artifact.getId());
        artifact.setContentType("application/zip");
        artifact.setSizeBytes(10);
        artifact.setFileCount(1);
        artifact.setManifestJson(List.of());
        artifact.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        artifacts.saveAndFlush(artifact);

        SkillVersionEntity version = new SkillVersionEntity();
        version.setId(UUID.randomUUID());
        version.setSkillId(skillId);
        version.setVersion("1.0.0");
        version.setChannel("stable");
        version.setArtifactId(artifact.getId());
        version.setStatus("pending_review");
        version.setChangelog("");
        version.setCreatedBy(publisher.id());
        version.setManifestJson(List.of());
        version.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        version.setSubmittedAt(OffsetDateTime.now(ZoneOffset.UTC));
        versions.saveAndFlush(version);

        ScanEntity scan = new ScanEntity();
        scan.setId(UUID.randomUUID());
        scan.setSkillVersionId(version.getId());
        scan.setScannerName("probe");
        scan.setScannerVersion("1");
        scan.setStatus("passed");
        scan.setFindingsJson(List.of());
        scan.setSummary("通过");
        scan.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC));
        scan.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        scans.saveAndFlush(scan);
        return version.getId();
    }
}
