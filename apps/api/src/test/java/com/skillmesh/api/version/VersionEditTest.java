package com.skillmesh.api.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.support.DatabaseCleaner;
import com.skillmesh.api.support.TestArtifacts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
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
 * 编辑文件 → 产出新版本。
 *
 * 版本不可变：编辑不动已发布的版本，而是重打包出一个新版本。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VersionEditTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private VersionEditService edits;

    private String auth;

    @BeforeEach
    void 准备() throws Exception {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
    }

    @Test
    void 改文件产出新版本且旧版本不变() throws Exception {
        UUID skillId = createSkill("editable");
        publishVersion(skillId, "1.0.0", TestArtifacts.zipOf(Map.of(
            "SKILL.md", "# 旧内容\n",
            "references/guide.md", "指南\n")));

        mockMvc.perform(put("/api/v1/skills/{id}/versions/{v}/file", skillId, "1.0.0")
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "path", "SKILL.md", "content", "# 新内容\n", "changelog", "改标题"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value("1.0.1"))
            .andExpect(jsonPath("$.status").value("published"))
            .andExpect(jsonPath("$.file_count").value(2));

        // 新版本的内容是改过的
        mockMvc.perform(get("/api/v1/skills/{id}/versions/{v}/file", skillId, "1.0.1")
                .header("Authorization", auth).param("path", "SKILL.md"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value("# 新内容\n"));

        // 旧版本原封不动——版本不可变
        mockMvc.perform(get("/api/v1/skills/{id}/versions/{v}/file", skillId, "1.0.0")
                .header("Authorization", auth).param("path", "SKILL.md"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value("# 旧内容\n"));
    }

    /** 没被改动的文件要原样带过去。 */
    @Test
    void 改一个文件不影响其它文件() throws Exception {
        UUID skillId = createSkill("partial");
        publishVersion(skillId, "1.0.0", TestArtifacts.zipOf(Map.of(
            "SKILL.md", "# 标题\n",
            "references/guide.md", "指南正文\n")));

        mockMvc.perform(put("/api/v1/skills/{id}/versions/{v}/file", skillId, "1.0.0")
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "path", "SKILL.md", "content", "# 改过了\n"))))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/skills/{id}/versions/{v}/file", skillId, "1.0.1")
                .header("Authorization", auth).param("path", "references/guide.md"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value("指南正文\n"));
    }

    /** 路径穿越要拒：路径会进 zip 的 entry 名，安装器会照着写文件。 */
    @Test
    void 路径穿越被拒() throws Exception {
        UUID skillId = createSkill("traversal");
        publishVersion(skillId, "1.0.0", TestArtifacts.zipOf(Map.of("SKILL.md", "# x\n")));

        mockMvc.perform(put("/api/v1/skills/{id}/versions/{v}/file", skillId, "1.0.0")
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "path", "../../evil.sh", "content", "rm -rf /\n"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** 别人的私有 Skill 不能编辑。 */
    @Test
    void 别人的私有Skill不能编辑() throws Exception {
        UUID skillId = createSkill("private-one");
        publishVersion(skillId, "1.0.0", TestArtifacts.zipOf(Map.of("SKILL.md", "# 秘密\n")));

        UUID strangerId = UUID.randomUUID();
        jdbc.update("insert into users (id, oidc_issuer, oidc_subject, display_name, status) "
            + "values (?, 'https://kc', ?, 'Stranger', 'active')", strangerId, "stranger-" + strangerId);
        CurrentUser stranger = new CurrentUser(strangerId, "stranger-" + strangerId, "Stranger", null);

        ApiException err = catchThrowableOfType(
            () -> edits.edit(stranger, skillId, "1.0.0",
                new com.skillmesh.api.version.dto.EditFileRequest(
                    "SKILL.md", "# 改了\n", null, null, null)),
            ApiException.class);
        assertThat(err).as("别人的私有 Skill 不该能编辑").isNotNull();
        assertThat(err.status()).isEqualTo(404);
    }

    /**
     * 团队可见的 Skill：不是团队成员的普通成员编辑不了。
     *
     * 回的是 404 不是 403——他连这个 Skill 都看不见（团队可见只对团队成员开），
     * 403 反而泄露了它存在。
     */
    @Test
    void 非团队成员不能编辑() throws Exception {
        UUID orgId = createOrg("acme");
        UUID teamId = createTeam(orgId, "platform");
        UUID skillId = createTeamSkill(orgId, teamId, "runbook");
        publishVersion(skillId, "1.0.0", TestArtifacts.zipOf(Map.of("SKILL.md", "# x\n")));

        UUID memberId = UUID.randomUUID();
        jdbc.update("insert into users (id, oidc_issuer, oidc_subject, display_name, status) "
            + "values (?, 'https://kc', ?, 'Member', 'active')", memberId, "mem-" + memberId);
        jdbc.update("insert into organization_memberships "
            + "(organization_id, user_id, role, joined_at) values (?, ?, ?, now())",
            orgId, memberId, "member");
        CurrentUser member = new CurrentUser(memberId, "mem-" + memberId, "Member", null);

        ApiException err = catchThrowableOfType(
            () -> edits.edit(member, skillId, "1.0.0",
                new com.skillmesh.api.version.dto.EditFileRequest(
                    "SKILL.md", "# 改了\n", null, null, null)),
            ApiException.class);
        assertThat(err).as("不是团队成员的编辑不了团队可见的 Skill").isNotNull();
        assertThat(err.status()).isEqualTo(404);
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

    private UUID createOrg(String slug) throws Exception {
        String resp = mockMvc.perform(post("/api/v1/organizations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", slug, "name", slug))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).path("id").asText());
    }

    private UUID createTeam(UUID orgId, String slug) throws Exception {
        String resp = mockMvc.perform(post("/api/v1/organizations/{id}/teams", orgId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", slug, "name", slug))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).path("id").asText());
    }

    private UUID createTeamSkill(UUID orgId, UUID teamId, String slug) throws Exception {
        String resp = mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "slug", slug, "name", slug, "description", "",
                    "visibility", "team",
                    "organization_id", orgId.toString(),
                    "owner_team_id", teamId.toString()))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).path("id").asText());
    }

    /** 建版本、直传、校验完——版本这时才有制品。 */
    private void publishVersion(UUID skillId, String version, byte[] zip) throws Exception {
        String digest = TestArtifacts.sha256(zip);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("channel", "stable");
        body.put("size_bytes", zip.length);
        body.put("sha256", digest);
        body.put("file_count", 1);
        body.put("manifest", List.of(Map.of(
            "path", "SKILL.md", "sha256", digest, "size_bytes", zip.length)));

        String created = mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
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
            HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isEqualTo(200);
    }
}
