package com.skillmesh.api.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 撤回不可逆（详细设计 §10）。
 *
 * 撤回的约定是「不可逆，要恢复得发新版本」——能重新送审或重新发布的话，
 * 撤回就只是个待办提示。两条路径都要拒。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RevokeIrreversibleTest {

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

    /**
     * 撤回了的版本不能再被上传会话转成已发布。
     *
     * 这条路走的是 UploadService.complete：会话是撤回之前开的，
     * 撤回之后收尾时如果还放行，撤回就等于没撤。
     */
    @Test
    void 撤回后不能再被上传会话发布() throws Exception {
        UUID skillId = createSkill("revoked-republish");
        byte[] zip = TestArtifacts.zipOf("SKILL.md", "# Revoked\n");

        // 先开一个上传会话（版本此时还是 draft）
        String created = mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "version", "1.0.0", "channel", "stable", "size_bytes", zip.length,
                    "sha256", TestArtifacts.sha256(zip), "file_count", 1,
                    "manifest", List.of(Map.of("path", "SKILL.md",
                        "sha256", TestArtifacts.sha256(zip), "size_bytes", zip.length))))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var node = json.readTree(created);
        String sessionId = node.path("upload").path("session_id").asText();

        // 但没有制品的版本撤不了（DDL 要求 revoked 必须带制品）——
        // 先把包传上去并发布，再撤回。
        putToStorage(node.path("upload").path("url").asText(), zip);
        mockMvc.perform(post("/api/v1/uploads/{id}/complete", sessionId)
                .header("Authorization", auth))
            .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/skills/{id}/versions/{v}/revoke", skillId, "1.0.0")
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"撤回后再测收尾\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("revoked"));

        // 撤回后状态没被改回去
        mockMvc.perform(get("/api/v1/skills/{id}/versions/{v}", skillId, "1.0.0")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("revoked"));
    }

    /**
     * 撤回后不能再重新送审。
     *
     * 送审那条路走 ReviewService.submit：撤回了的版本进队列、过审就又变成已发布。
     */
    @Test
    void 撤回后不能重新送审() throws Exception {
        UUID skillId = createSkill("revoked-resubmit");
        byte[] zip = TestArtifacts.zipOf("SKILL.md", "# Resubmit\n");
        publish(skillId, "1.0.0", zip);

        mockMvc.perform(post("/api/v1/skills/{id}/versions/{v}/revoke", skillId, "1.0.0")
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"撤回后再测送审\"}"))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/skills/{id}/versions/{v}/submit", skillId, "1.0.0")
                .header("Authorization", auth))
            .andExpect(status().isConflict());
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

    private void publish(UUID skillId, String version, byte[] zip) throws Exception {
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
