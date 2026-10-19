package com.skillmesh.api.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.support.DatabaseCleaner;
import com.skillmesh.api.support.TestArtifacts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 同步变更集：云端冲突判定 + 幂等键（设计文档 §6.3、§12）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SyncChangesetTest {

    private static final String OLD_BASE = "a".repeat(64);
    private static final String LOCAL_NEW = "b".repeat(64);

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
    void 云端被别的设备改过就判冲突() throws Exception {
        UUID skillId = createSkill("shared");
        publish(skillId, "1.0.0");
        UUID deviceId = registerDevice();

        // 云端已经有 1.0.0，客户端拿的基线对不上 → 冲突，不自动覆盖
        var first = json.readTree(mockMvc.perform(changeset(deviceId, skillId, "key-1",
                    OLD_BASE, LOCAL_NEW))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("conflict"))
                .andExpect(jsonPath("$.conflicts[0].type").value("both_modified"))
                .andReturn().getResponse().getContentAsString());
        String cloudHash = first.path("cloud_hash").asText();
        assertThat(cloudHash).isNotEmpty();

        // 拿服务端给的云端摘要当新基线：对上了 → 正常受理
        mockMvc.perform(changeset(deviceId, skillId, "key-2", cloudHash, cloudHash))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("completed"));

        // 只有本地改了（基线=云端）→ 顺着上传，不算冲突（设计文档 §6.3）
        mockMvc.perform(changeset(deviceId, skillId, "key-3", cloudHash, LOCAL_NEW))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("completed"));
    }

    @Test
    void 同一个幂等键只产生一次变更集() throws Exception {
        UUID skillId = createSkill("idem");
        UUID deviceId = registerDevice();

        String first = mockMvc.perform(changeset(deviceId, skillId, "same-key",
                OLD_BASE, OLD_BASE))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(changeset(deviceId, skillId, "same-key",
                OLD_BASE, LOCAL_NEW))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        // 断网重试必须落回同一条记录，不能变成第二次同步
        assertThat(json.readTree(second).path("changeset_id").asText())
            .isEqualTo(json.readTree(first).path("changeset_id").asText());
        assertThat(json.readTree(second).path("status").asText()).isEqualTo("completed");
    }

    private MockHttpServletRequestBuilder changeset(UUID deviceId, UUID skillId, String key,
                                                    String base, String local) throws Exception {
        return post("/api/v1/sync/changesets")
            .header("Authorization", auth)
            .header("X-Device-Id", deviceId.toString())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of(
                "skill_id", skillId.toString(),
                "direction", "upload",
                "base_hash", base,
                "local_hash", local,
                "local_path", "skills/demo",
                "manifest", List.of(Map.of("path", "SKILL.md", "sha256", local, "size_bytes", 10)))));
    }

    private UUID registerDevice() throws Exception {
        String resp = mockMvc.perform(post("/api/v1/devices").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_key\":\"test-device\",\"platform\":\"test\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).path("id").asText());
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

    private void publish(UUID skillId, String version) throws Exception {
        byte[] zip = TestArtifacts.zipOf("SKILL.md", "# " + version + "\n");
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
            HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isEqualTo(200);
        mockMvc.perform(post("/api/v1/uploads/{id}/complete",
                node.path("upload").path("session_id").asText())
                .header("Authorization", auth))
            .andExpect(status().isAccepted());
    }
}
