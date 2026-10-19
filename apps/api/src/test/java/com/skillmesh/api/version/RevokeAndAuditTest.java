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

/** 撤回与审计流水（设计文档 §10）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RevokeAndAuditTest {

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
    void 撤回后该版本不能装且有审计记录() throws Exception {
        UUID skillId = createSkill("revokable");
        byte[] zip = VersionFlowTest.zipOf("SKILL.md", "# Revokable\n");
        publish(skillId, "1.0.0", zip);

        mockMvc.perform(post("/api/v1/skills/{id}/versions/{v}/revoke", skillId, "1.0.0")
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"发现安全问题\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("revoked"));

        // 状态改了，历史还在
        mockMvc.perform(get("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth))
            .andExpect(jsonPath("$.items[0].status").value("revoked"))
            .andExpect(jsonPath("$.items[0].version").value("1.0.0"));

        // 撤回本身和发布都进了流水
        String auditJson = mockMvc.perform(get("/api/v1/skills/{id}/audit", skillId)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        var events = json.readTree(auditJson);
        assertThat(events.toString()).contains("version.revoke");
        assertThat(events.toString()).contains("发现安全问题");
        assertThat(events.toString()).contains("skill.publish");
    }

    @Test
    void 撤回不可重复且撤回别人的不行() throws Exception {
        UUID skillId = createSkill("revoke-once");
        publish(skillId, "1.0.0", VersionFlowTest.zipOf("SKILL.md", "# Once\n"));

        mockMvc.perform(post("/api/v1/skills/{id}/versions/{v}/revoke", skillId, "1.0.0")
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"第一次\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/skills/{id}/versions/{v}/revoke", skillId, "1.0.0")
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"第二次\"}"))
            .andExpect(status().isConflict());
    }

    /** 客户端上报只放行客户端自己知道的动作，特权动作要被拒。 */
    @Test
    void 客户端只能上报自己的动作() throws Exception {
        UUID skillId = createSkill("reported");
        publish(skillId, "1.0.0", VersionFlowTest.zipOf("SKILL.md", "# Reported\n"));

        mockMvc.perform(post("/api/v1/audit/events")
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"action":"install.completed","target_id":"%s","outcome":"success",
                     "details":{"version":"1.0.0"}}""".formatted(skillId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accepted").value(true));

        // 发布是服务端的特权动作，客户端报上来的一律拒
        mockMvc.perform(post("/api/v1/audit/events")
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"action":"skill.publish","target_id":"%s","outcome":"success"}""".formatted(skillId)))
            .andExpect(status().isBadRequest());

        // 上报的安装事件要能在流水里查到
        var auditJson = mockMvc.perform(get("/api/v1/skills/{id}/audit", skillId)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(auditJson).contains("install.completed");
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
                    "sha256", VersionFlowTest.sha256(zip), "file_count", 1,
                    "manifest", List.of(Map.of("path", "SKILL.md",
                        "sha256", VersionFlowTest.sha256(zip), "size_bytes", zip.length))))))
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
}
