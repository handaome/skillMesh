package com.skillmesh.api.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.org.OrgService;
import com.skillmesh.api.review.dto.PendingReviewItem;
import com.skillmesh.api.skill.SkillService;
import com.skillmesh.api.skill.dto.CreateSkillRequest;
import com.skillmesh.api.support.DatabaseCleaner;
import com.skillmesh.api.support.TestArtifacts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
     * 造一个待审版本：团队可见的 Skill 发上去会停在 pending_review。
     * 方法名沿用原版 brief——它其实造的是待审版本，不是已发布版本。
     */
    private void publish(UUID skillId, String version) throws Exception {
        byte[] zip = TestArtifacts.zipOf("SKILL.md", "# Runbook\n");
        String created = mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "version", version, "channel", "stable", "size_bytes", zip.length,
                    "sha256", TestArtifacts.sha256(zip), "file_count", 1,
                    "manifest", List.of(Map.of("path", "SKILL.md",
                        "sha256", TestArtifacts.sha256(zip), "size_bytes", zip.length))))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var node = json.readTree(created);
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
