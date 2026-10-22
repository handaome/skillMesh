package com.skillmesh.api.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** 在线浏览 Skill 版本里的文件。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VersionFileBrowseTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private VersionFileService versionFiles;

    private String auth;

    @BeforeEach
    void 准备() throws Exception {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
    }

    @Test
    void 列出制品里的文件() throws Exception {
        UUID skillId = createSkill("browse");
        publishVersion(skillId, "1.0.0", TestArtifacts.zipOf(Map.of(
            "SKILL.md", "# 浏览\n",
            "references/api.md", "接口说明\n")));

        mockMvc.perform(get("/api/v1/skills/{id}/versions/{v}/files", skillId, "1.0.0")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.file_count").value(2))
            .andExpect(jsonPath("$.files[0].path").value("SKILL.md"))
            .andExpect(jsonPath("$.files[1].path").value("references/api.md"))
            // 大小与摘要来自服务端从真 zip 算出的清单
            .andExpect(jsonPath("$.files[0].sha256").value(
                TestArtifacts.sha256("# 浏览\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    void 读出文本文件的内容() throws Exception {
        UUID skillId = createSkill("read-text");
        publishVersion(skillId, "1.0.0", TestArtifacts.zipOf(Map.of(
            "SKILL.md", "# 正文\n\n第一段。\n")));

        mockMvc.perform(get("/api/v1/skills/{id}/versions/{v}/file", skillId, "1.0.0")
                .header("Authorization", auth).param("path", "SKILL.md"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.binary").value(false))
            .andExpect(jsonPath("$.content").value("# 正文\n\n第一段。\n"))
            .andExpect(jsonPath("$.path").value("SKILL.md"));
    }

    @Test
    void 不存在的文件返回404() throws Exception {
        UUID skillId = createSkill("missing-file");
        publishVersion(skillId, "1.0.0", TestArtifacts.zipOf(Map.of("SKILL.md", "# X\n")));

        mockMvc.perform(get("/api/v1/skills/{id}/versions/{v}/file", skillId, "1.0.0")
                .header("Authorization", auth).param("path", "nope.md"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /** 看不见的 Skill 连文件清单都不给——文件内容不比元数据更该藏。 */
    @Test
    void 别人的私有Skill看不到文件() throws Exception {
        UUID skillId = createSkill("private-one");
        publishVersion(skillId, "1.0.0", TestArtifacts.zipOf(Map.of("SKILL.md", "# 秘密\n")));

        UUID strangerId = UUID.randomUUID();
        jdbc.update("insert into users (id, oidc_issuer, oidc_subject, display_name, status) "
            + "values (?, 'https://kc', ?, 'Stranger', 'active')", strangerId, "stranger-" + strangerId);
        CurrentUser stranger = new CurrentUser(strangerId, "stranger-" + strangerId, "Stranger", null);

        ApiException err = catchThrowableOfType(
            () -> versionFiles.list(stranger, skillId, "1.0.0"), ApiException.class);
        assertThat(err).as("别人的私有 Skill 不该看到文件清单").isNotNull();
        assertThat(err.status()).isEqualTo(404);
    }

    /** 还没上传制品的版本没有文件可看。 */
    @Test
    void 没有制品的版本返回404() throws Exception {
        UUID skillId = createSkill("no-artifact");
        mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"version":"1.0.0","channel":"stable","size_bytes":1,"file_count":1,
                     "sha256":"%s","manifest":[{"path":"SKILL.md","sha256":"%s","size_bytes":1}]}
                    """.formatted("a".repeat(64), "a".repeat(64))))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/skills/{id}/versions/{v}/files", skillId, "1.0.0")
                .header("Authorization", auth))
            .andExpect(status().isNotFound());
    }

    /**
     * 早期入库的制品，artifacts.manifest_json 是空的。
     *
     * 那时清单还没落库，但 zip 里文件都在。此时清单要从 zip 现场重算——
     * 返回空列表等于告诉用户「这个 Skill 没有文件」，那是假的。
     */
    @Test
    void 制品没有落库的清单时从zip重算() throws Exception {
        UUID skillId = createSkill("legacy-artifact");
        publishVersion(skillId, "1.0.0", TestArtifacts.zipOf(Map.of(
            "SKILL.md", "# 老数据\n",
            "references/guide.md", "指南\n")));

        // 把制品的清单清空，模拟早期入库的样子
        jdbc.update("update artifacts set manifest_json = '[]'::jsonb "
            + "where id = (select artifact_id from skill_versions where skill_id = ?)",
            skillId);

        mockMvc.perform(get("/api/v1/skills/{id}/versions/{v}/files", skillId, "1.0.0")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.file_count").value(2))
            .andExpect(jsonPath("$.files[0].path").value("SKILL.md"))
            .andExpect(jsonPath("$.files[1].path").value("references/guide.md"));
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
