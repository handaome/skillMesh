package com.skillmesh.api.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.org.OrgService;
import com.skillmesh.api.review.dto.PendingReviewItem;
import com.skillmesh.api.skill.SkillService;
import com.skillmesh.api.skill.dto.CreateSkillRequest;
import com.skillmesh.api.support.DatabaseCleaner;
import com.skillmesh.api.support.QueryCounter;
import com.skillmesh.api.support.TestArtifacts;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 审核队列：只列我审得了的（设计文档 §5.3）。
 *
 * 越权那几条走服务层：测试客户端只有一个真实令牌，
 * 换个身份只能自己造 CurrentUser（同 MemberManagementTest 的做法）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewQueueTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;
    @Autowired private OrgService orgs;
    @Autowired private SkillService skills;
    @Autowired private ReviewQueueService queue;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private String auth;
    private CurrentUser owner;

    @BeforeEach
    void 准备() throws Exception {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
        owner = currentUser();
    }

    @Test
    void 团队待审版本出现在审核队列里() throws Exception {
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = skills.create(owner, new CreateSkillRequest("runbook", "Runbook", "",
            "team", orgId, teamId, List.of(), Map.of(), null)).id();
        publish(skillId, "1.0.0");

        mockMvc.perform(get("/api/v1/reviews").param("status", "pending_review")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].skill_slug").value("runbook"))
            .andExpect(jsonPath("$[0].version").value("1.0.0"))
            .andExpect(jsonPath("$[0].scan.status").exists());
    }

    @Test
    void 非reviewer看不见待审版本() throws Exception {
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = skills.create(owner, new CreateSkillRequest("runbook", "Runbook", "",
            "team", orgId, teamId, List.of(), Map.of(), null)).id();
        publish(skillId, "1.0.0");

        CurrentUser member = stranger();
        // 只入团队、角色是 member → 不是 reviewer → 看不见
        orgs.addTeamMember(owner, teamId, member.id(), "member");

        assertThat(queue.pending(member, "pending_review", 50))
            .as("member 看不见待审队列").isEmpty();
        assertThat(queue.pending(owner, "pending_review", 50))
            .as("owner 能看见待审队列").hasSize(1);
    }

    @Test
    void 审不了的版本不能占掉分页窗口() throws Exception {
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamA = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID teamB = orgs.createTeam(owner, orgId, "data", "Data", null).getId();
        UUID skillA = skills.create(owner, new CreateSkillRequest("runbook-a", "Runbook A", "",
            "team", orgId, teamA, List.of(), Map.of(), null)).id();
        UUID skillB = skills.create(owner, new CreateSkillRequest("runbook-b", "Runbook B", "",
            "team", orgId, teamB, List.of(), Map.of(), null)).id();

        // 这人在 A 队是 reviewer，在 B 队只是 member——只有 A 队的版本归他审
        CurrentUser reviewer = stranger();
        orgs.addTeamMember(owner, teamA, reviewer.id(), "reviewer");
        orgs.addTeamMember(owner, teamB, reviewer.id(), "member");

        publish(skillA, "1.0.0");
        publish(skillB, "1.0.0");

        // 窗口只有一条：B 更新（排在前），但审不了——可见性必须在 SQL 里判，
        // 否则它会占掉窗口，A 队的版本永远看不见。
        assertThat(queue.pending(reviewer, "pending_review", 1))
            .as("分页窗口只给审得了的版本")
            .extracting(PendingReviewItem::skill_slug)
            .containsExactly("runbook-a");
    }

    @Test
    void 审核详情带SKILL正文与文件级差异() throws Exception {
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = skills.create(owner, new CreateSkillRequest("runbook", "Runbook", "",
            "team", orgId, teamId, List.of(), Map.of(), null)).id();
        publish(skillId, "1.0.0");

        mockMvc.perform(get("/api/v1/reviews/{id}/{v}", skillId, "1.0.0")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.skill_md").value(org.hamcrest.Matchers.containsString("Runbook")))
            .andExpect(jsonPath("$.diff.base_version").value(""))
            .andExpect(jsonPath("$.diff.files[0].path").value("SKILL.md"))
            .andExpect(jsonPath("$.diff.files[0].kind").value("added"));
    }

    /**
     * 队列里的 submitted_at 是进队列的时间，不是建版本的时间。
     *
     * 上午建好版本（draft，没制品），下午才上传制品触发扫描与送审——
     * 队列里显示的应当是下午那个时间，不是建版本的时间。
     */
    @Test
    void 送审时间是进队列的时间不是建版本的时间() throws Exception {
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = skills.create(owner, new CreateSkillRequest("runbook", "Runbook", "",
            "team", orgId, teamId, List.of(), Map.of(), null)).id();

        byte[] zip = TestArtifacts.zipOf("SKILL.md", "# Runbook\n");
        var node = createVersion(skillId, "1.0.0", zip);
        // 建版本的时间挪到一小时前——送审时间不该跟着它
        jdbc.update("update skill_versions set created_at = now() - interval '1 hour' "
            + "where id = ?", UUID.fromString(node.path("version_id").asText()));
        uploadAndComplete(node, zip);

        String queue = mockMvc.perform(get("/api/v1/reviews").param("status", "pending_review")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        OffsetDateTime submitted = OffsetDateTime.parse(
            json.readTree(queue).get(0).path("submitted_at").asText());

        assertThat(submitted).as("送审时间应是进队列这一刻")
            .isAfter(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5));
    }

    /**
     * 审核队列一页的关联数据一次查齐。
     *
     * 原来每条待审版本都要再查技能、扫描、来源、人四次——一页 50 条就是 200 次往返，
     * 本机到远程库一次往返几十毫秒，这个页面直接卡住。
     */
    @Test
    void 审核队列一次查齐关联数据() throws Exception {
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = skills.create(owner, new CreateSkillRequest("runbook", "Runbook", "",
            "team", orgId, teamId, List.of(), Map.of(), null)).id();
        insertPendingVersions(skillId, 4);

        QueryCounter counter = new QueryCounter(entityManagerFactory);
        long queries = counter.count(() -> {
            List<PendingReviewItem> items = queue.pending(owner, "pending_review", 50);
            assertThat(items).as("待审队列该有这 4 条").hasSize(4);
        });

        assertThat(queries).as("队列页的查询条数不该随条目数增长")
            .isLessThanOrEqualTo(8);
    }

    /**
     * 造 count 个待审版本（带制品与扫描）。
     *
     * 直接写库，不走对象存储——这里只关心队列页的查询条数。
     */
    private void insertPendingVersions(UUID skillId, int count) {
        for (int i = 1; i <= count; i++) {
            UUID artifactId = UUID.randomUUID();
            jdbc.update("insert into artifacts (id, sha256, storage_key, size_bytes, file_count, "
                + "manifest_json, created_at) values (?, ?, ?, 10, 1, '[]'::jsonb, now())",
                artifactId, "%064d".formatted(i), "test/" + artifactId);
            UUID versionId = UUID.randomUUID();
            jdbc.update("insert into skill_versions (id, skill_id, version, channel, artifact_id, "
                + "manifest_json, status, created_by, created_at, submitted_at) "
                + "values (?, ?, ?, 'stable', ?, '[]'::jsonb, 'pending_review', ?, now(), now())",
                versionId, skillId, "1.0." + i, artifactId, owner.id());
            jdbc.update("insert into skill_scans (id, skill_version_id, scanner_name, "
                + "scanner_version, status, findings_json, summary, started_at) "
                + "values (?, ?, 'skillmesh-server-scan', '1', 'passed', '[]'::jsonb, 'ok', now())",
                UUID.randomUUID(), versionId);
        }
    }

    /**
     * 造一个待审版本：团队可见的 Skill 发上去会停在 pending_review。
     * 方法名沿用原版 brief——它其实造的是待审版本，不是已发布版本。
     */
    private void publish(UUID skillId, String version) throws Exception {
        byte[] zip = TestArtifacts.zipOf("SKILL.md", "# Runbook\n");
        uploadAndComplete(createVersion(skillId, version, zip), zip);
    }

    /** 只建版本（draft，没有制品），返回建版本响应。 */
    private JsonNode createVersion(UUID skillId, String version, byte[] zip) throws Exception {
        String created = mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "version", version, "channel", "stable", "size_bytes", zip.length,
                    "sha256", TestArtifacts.sha256(zip), "file_count", 1,
                    "manifest", List.of(Map.of("path", "SKILL.md",
                        "sha256", TestArtifacts.sha256(zip), "size_bytes", zip.length))))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(created);
    }

    /** 直传包再收尾——版本从 draft 进 pending_review。 */
    private void uploadAndComplete(JsonNode node, byte[] zip) throws Exception {
        HttpResponse<String> put = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(node.path("upload").path("url").asText()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(zip)).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(put.statusCode()).isEqualTo(200);
        mockMvc.perform(post("/api/v1/uploads/{id}/complete",
                node.path("upload").path("session_id").asText())
                .header("Authorization", auth))
            .andExpect(status().isAccepted());
    }

    private CurrentUser currentUser() throws Exception {
        String body = mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
            .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).path("user").path("id").asText());
        return new CurrentUser(id, "owner", "Owner", "owner@example.com");
    }

    /** 造一个真实用户行——成员表对 users 有外键，光给个 UUID 插不进去。 */
    private CurrentUser stranger() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, oidc_issuer, oidc_subject, display_name, status) "
            + "values (?, 'https://kc', ?, 'Stranger', 'active')", id, "stranger-" + id);
        return new CurrentUser(id, "stranger-" + id, "Stranger", null);
    }
}
