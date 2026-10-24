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
 * 基线前置条件要盖住整个上传窗口，不只是建版本那一刻。
 *
 * 客户端做决策时看到的云端摘要，一路带到服务端。
 * 中途云端被别人动过——这次上传会把那份改动静默盖掉，服务端要拒。
 *
 * 原来只在建版本时查一次：查完到转正之间有个窗口，窗口里别人发布了不会被拦。
 * 现在转正时再对一次，那个窗口才被堵上。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BaselineWindowTest {

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
     * 建版本时基线是对的，但传完之前云端被别的设备改了：
     * 转正必须被拒——不然那份改动就被静默盖掉了。
     */
    @Test
    void 转正前云端被改动则拒绝() throws Exception {
        UUID skillId = createSkill("baseline-window");
        publishVersion(skillId, "1.0.0", "# 原始\n");
        String base = cloudDigest(skillId);

        // 按正确的基线建版本：这一步该通过。
        String session = openSession(skillId, "1.0.1", "# 新的一版\n", base);
        assertThat(session).isNotBlank();

        // 窗口里：别的设备发布了新版本，云端摘要变了。
        publishVersion(skillId, "1.0.2", "# 别人发的\n");

        // 转正：基线对不上，必须拒。
        mockMvc.perform(post("/api/v1/uploads/{id}/complete", session)
                .header("Authorization", auth))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
            .andExpect(jsonPath("$.detail").value(
                org.hamcrest.Matchers.containsString("重新对账")));
    }

    /** 窗口里云端没被动过：转正照旧通过。 */
    @Test
    void 转正前云端没动则放行() throws Exception {
        UUID skillId = createSkill("baseline-window-ok");
        publishVersion(skillId, "1.0.0", "# 原始\n");
        String base = cloudDigest(skillId);

        String session = openSession(skillId, "1.0.1", "# 新的一版\n", base);

        mockMvc.perform(post("/api/v1/uploads/{id}/complete", session)
                .header("Authorization", auth))
            .andExpect(status().isAccepted());
    }

    /** 不带基线的普通发布：照旧放行，不受复检影响。 */
    @Test
    void 不带基线的发布照旧放行() throws Exception {
        UUID skillId = createSkill("baseline-window-none");
        publishVersion(skillId, "1.0.0", "# 原始\n");

        String session = openSession(skillId, "1.0.1", "# 新的一版\n", "");
        mockMvc.perform(post("/api/v1/uploads/{id}/complete", session)
                .header("Authorization", auth))
            .andExpect(status().isAccepted());
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

    /** 云端当前已发布版本的内容摘要：客户端带的就是它。 */
    private String cloudDigest(UUID skillId) throws Exception {
        String resp = mockMvc.perform(get("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        var items = json.readTree(resp).path("items");
        for (var item : items) {
            if ("published".equals(item.path("status").asText())) {
                return item.path("artifact").path("content_digest").asText();
            }
        }
        return "";
    }

    /** 发一版并完成上传，让它成为云端的已发布版本。 */
    private void publishVersion(UUID skillId, String version, String content) throws Exception {
        byte[] zip = TestArtifacts.zipOf(Map.of("SKILL.md", content));
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

    /** 按给定基线建版本并把包传上去，返回上传会话 id。 */
    private String openSession(UUID skillId, String version, String content, String base)
            throws Exception {
        byte[] zip = TestArtifacts.zipOf(Map.of("SKILL.md", content));
        String created = mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "version", version, "channel", "stable", "size_bytes", zip.length,
                    "sha256", TestArtifacts.sha256(zip), "file_count", 1,
                    "manifest", List.of(Map.of("path", "SKILL.md",
                        "sha256", TestArtifacts.sha256(zip), "size_bytes", zip.length)),
                    "expected_base_revision", base))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var node = json.readTree(created);
        putToStorage(node.path("upload").path("url").asText(), zip);
        return node.path("upload").path("session_id").asText();
    }

    private void putToStorage(String url, byte[] data) throws Exception {
        HttpResponse<String> put = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(put.statusCode()).isEqualTo(200);
    }
}
