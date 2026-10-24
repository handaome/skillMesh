package com.skillmesh.api.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 基线前置条件（详细设计 §12 的 If-Match / expected_revision）。
 *
 * 客户端做决策时看到的云端摘要，必须一路带到服务端：中途云端被别人动过，
 * 这次上传会把那份改动静默盖掉——服务端要拒（409），让客户端重新对账。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VersionBaselineTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;

    private String auth;

    @BeforeEach
    void 准备() throws Exception {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
    }

    @Test
    void 基线对不上时拒绝对账() throws Exception {
        UUID skillId = createSkill("baseline-demo");
        publishVersion(skillId, "1.0.0");

        // 客户端看到的云端摘要是错的（这里用一串 b，凑满 64 位十六进制）
        mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(body("1.0.1", "b".repeat(64))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
            .andExpect(jsonPath("$.detail").value(
                org.hamcrest.Matchers.containsString("重新对账")));
    }

    @Test
    void 基线对得上时放行() throws Exception {
        UUID skillId = createSkill("baseline-ok");
        publishVersion(skillId, "1.0.0");

        // 不带基线：普通发布照旧放行
        mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(body("1.0.1", "")))
            .andExpect(status().isCreated());
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

    /** 发一版并完成上传，让它成为云端的已发布版本。 */
    private void publishVersion(UUID skillId, String version) throws Exception {
        byte[] zip = TestArtifacts.zipOf(Map.of("SKILL.md", "# Demo\n"));
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
        java.net.http.HttpResponse<String> put = java.net.http.HttpClient.newHttpClient().send(
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(node.path("upload").path("url").asText()))
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(zip)).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isEqualTo(200);
        mockMvc.perform(post("/api/v1/uploads/{id}/complete",
                node.path("upload").path("session_id").asText())
                .header("Authorization", auth))
            .andExpect(status().isAccepted());
    }

    /** 建版本请求体；baseRevision 为空串表示不带基线。 */
    private String body(String version, String baseRevision) throws Exception {
        byte[] zip = TestArtifacts.zipOf(Map.of("SKILL.md", "# Demo\n"));
        return json.writeValueAsString(Map.of(
            "version", version, "channel", "stable", "size_bytes", zip.length,
            "sha256", TestArtifacts.sha256(zip), "file_count", 1,
            "manifest", List.of(Map.of("path", "SKILL.md",
                "sha256", TestArtifacts.sha256(zip), "size_bytes", zip.length)),
            "expected_base_revision", baseRevision));
    }
}
