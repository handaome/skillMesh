package com.skillmesh.api.recycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.CurrentUser;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.support.DatabaseCleaner;
import com.skillmesh.api.support.TestArtifacts;

/**
 * 文件级回收站（设计文档 §6.4：删除传播走软删除、回收期、支持恢复）。
 *
 * 一版比上一版少的文件不是消失了：它进了回收站，回收期内能找回来。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecycleBinTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;
    @Autowired private com.skillmesh.api.org.OrgService orgs;
    @Autowired private com.skillmesh.api.review.ReviewService reviews;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String auth;

    @BeforeEach
    void 准备() throws Exception {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
    }

    /** 删掉的文件进回收站。 */
    @Test
    void 删掉的文件进回收站() throws Exception {
        UUID skillId = createSkill("recycle-demo");
        publishVersion(skillId, "1.0.0", Map.of("SKILL.md", "# 原始\n", "old.md", "# 要删的\n"));
        // 第二版少了 old.md：它被删了。
        publishVersion(skillId, "1.0.1", Map.of("SKILL.md", "# 原始\n"));

        mockMvc.perform(get("/api/v1/skills/{id}/recycle", skillId)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].path").value("old.md"))
            .andExpect(jsonPath("$[0].source_version").value("1.0.0"))
            // 字段名是下划线：客户端按契约读，驼峰会读不到。
            .andExpect(jsonPath("$[0].sourceVersion").doesNotExist())
            .andExpect(jsonPath("$[0].expires_at").exists());
    }

    /** 没删东西的版本不进回收站。 */
    @Test
    void 没删文件则回收站是空的() throws Exception {
        UUID skillId = createSkill("recycle-clean");
        publishVersion(skillId, "1.0.0", Map.of("SKILL.md", "# 原始\n"));
        publishVersion(skillId, "1.0.1", Map.of("SKILL.md", "# 改了\n"));

        mockMvc.perform(get("/api/v1/skills/{id}/recycle", skillId)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    /** 恢复：文件回到新版本里，内容还是原来那份。 */
    @Test
    void 恢复把文件放回新版本() throws Exception {
        UUID skillId = createSkill("recycle-restore");
        publishVersion(skillId, "1.0.0", Map.of("SKILL.md", "# 原始\n", "old.md", "# 要删的\n"));
        publishVersion(skillId, "1.0.1", Map.of("SKILL.md", "# 原始\n"));

        mockMvc.perform(post("/api/v1/skills/{id}/recycle/restore", skillId)
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("path", "old.md"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value("1.0.2"));

        // 新版本里文件回来了，内容跟删之前一样。
        String resp = mockMvc.perform(get("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        var first = json.readTree(resp).path("items").get(0);
        assertThat(first.path("version").asText()).isEqualTo("1.0.2");
        var paths = first.path("manifest").findValuesAsText("path");
        assertThat(paths).as("恢复后文件回来了").contains("old.md");
    }

    /**
     * 团队可见的 Skill：版本要过审才发布，删除也要在**过审那一刻**记进回收站。
     *
     * 只在转正那一刻记是不够的：团队/组织可见的版本转正时状态是 pending_review，
     * 真正发布是审核通过那一刻——不在这儿记，回收站在组织/团队场景下就永远是空的。
     */
    @Test
    void 团队版本过审后删除的文件也进回收站() throws Exception {
        CurrentUser owner = currentUser();
        UUID orgId = orgs.createOrganization(owner, "recycle-org", "Recycle Org").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = createTeamSkill(orgId, teamId, "team-recycle");
        // 审核人得是另一个人：自己发的东西自己审要被拒。
        CurrentUser reviewer = reviewerInTeam(teamId);

        // 第一版含 old.md，过审后发布。
        publishVersion(skillId, "1.0.0", Map.of("SKILL.md", "# 原始\n", "old.md", "# 要删的\n"));
        reviews.approve(reviewer, skillId, "1.0.0", "没问题");
        // 第二版少了 old.md，也过审。
        publishVersion(skillId, "1.0.1", Map.of("SKILL.md", "# 原始\n"));
        reviews.approve(reviewer, skillId, "1.0.1", "没问题");

        mockMvc.perform(get("/api/v1/skills/{id}/recycle", skillId)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].path").value("old.md"));
    }

    /** 造一个团队里的 reviewer（另一个人）。 */
    private CurrentUser reviewerInTeam(UUID teamId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, oidc_issuer, oidc_subject, display_name, status) "
            + "values (?, 'https://kc', ?, 'Reviewer', 'active')", id, "rev-" + id);
        jdbc.update("insert into team_memberships (team_id, user_id, role, joined_at) "
            + "values (?, ?, 'reviewer', now())", teamId, id);
        return new CurrentUser(id, "rev-" + id, "Reviewer", null);
    }

    private UUID createSkill(String slug) throws Exception {
        String resp = mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", slug, "name", slug,
                    "description", "", "visibility", "private"))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).path("id").asText());
    }

    /** 团队可见的 Skill。 */
    private UUID createTeamSkill(UUID orgId, UUID teamId, String slug) throws Exception {
        String resp = mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", slug, "name", slug,
                    "description", "", "visibility", "team",
                    "organization_id", orgId.toString(),
                    "owner_team_id", teamId.toString()))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).path("id").asText());
    }

    /** 当前登录的那个用户。 */
    private CurrentUser currentUser() throws Exception {
        String body = mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).path("user").path("id").asText());
        return new CurrentUser(id, "dev-user", "Dev", null);
    }

    private void publishVersion(UUID skillId, String version, Map<String, String> files)
            throws Exception {
        byte[] zip = TestArtifacts.zipOf(files);
        List<Map<String, Object>> manifest = new java.util.ArrayList<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            byte[] content = file.getValue().getBytes(StandardCharsets.UTF_8);
            manifest.add(Map.of("path", file.getKey(),
                "sha256", TestArtifacts.sha256(content),
                "size_bytes", content.length));
        }
        String created = mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "version", version, "channel", "stable", "size_bytes", zip.length,
                    "sha256", TestArtifacts.sha256(zip), "file_count", files.size(),
                    "manifest", manifest))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var node = json.readTree(created);
        putToStorage(node.path("upload").path("url").asText(), zip);
        mockMvc.perform(post("/api/v1/uploads/{id}/complete",
                node.path("upload").path("session_id").asText())
                .header("Authorization", auth))
            .andExpect(status().isAccepted());
    }

    private void putToStorage(String url, byte[] data) throws Exception {
        HttpResponse<String> put = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(put.statusCode()).isEqualTo(200);
    }
}
