package com.skillmesh.api.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/**
 * 上传收尾时的制品校验。
 *
 * 重名 entry 的包是非法制品：清单取最后一个、审核面板取第一个、CLI 安装器取最后一个，
 * 审核者看到的内容与客户端装到的不一样——审核队列这道闸就被绕过了。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UploadArtifactTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;

    private String auth;

    @BeforeEach
    void 准备() {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
    }

    @Test
    void 含重名entry的包上传要拒() throws Exception {
        UUID skillId = createSkill();
        byte[] zip = TestArtifacts.zipWithDuplicateEntry("SKILL.md", "# A\n", "# MALICIOUS\n");

        String sessionId = upload(skillId, "1.0.0", zip);

        mockMvc.perform(post("/api/v1/uploads/{id}/complete", sessionId)
                .header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.detail").value(containsString("SKILL.md")));
    }

    @Test
    void 正常的包照常发布() throws Exception {
        UUID skillId = createSkill();
        byte[] zip = TestArtifacts.zipOf("SKILL.md", "# A\n");

        String sessionId = upload(skillId, "1.0.0", zip);

        mockMvc.perform(post("/api/v1/uploads/{id}/complete", sessionId)
                .header("Authorization", auth))
            .andExpect(status().isAccepted());

        String body = mockMvc.perform(get("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).path("items").get(0).path("status").asText())
            .isEqualTo("published");
    }

    /**
     * 清单以服务端从真 zip 算的为准。
     *
     * 发布方自填的清单只是他的说法：这里声明「只有 SKILL.md」，
     * 真包里却还有 secret.sh。审核面板列的文件、content_digest、
     * 签名覆盖的摘要，都得是真包那份——不然审核的人看的是一份假的文件清单。
     */
    @Test
    void 声明的清单不可信时以真包为准() throws Exception {
        UUID skillId = createSkill();
        byte[] zip = TestArtifacts.zipOf(Map.of("SKILL.md", "# A\n", "secret.sh", "echo hi\n"));

        String sessionId = upload(skillId, "1.0.0", zip);

        mockMvc.perform(post("/api/v1/uploads/{id}/complete", sessionId)
                .header("Authorization", auth))
            .andExpect(status().isAccepted());

        String body = mockMvc.perform(get("/api/v1/skills/{id}/versions/{v}", skillId, "1.0.0")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        var manifest = json.readTree(body).path("manifest");
        assertThat(manifest).hasSize(2);
        assertThat(manifest.toString()).contains("secret.sh");
    }

    /** 建版本 → 直传包，返回上传会话 id。 */
    private String upload(UUID skillId, String version, byte[] zip) throws Exception {
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
        return node.path("upload").path("session_id").asText();
    }

    private UUID createSkill() throws Exception {
        String resp = mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", "artifact", "name", "artifact",
                    "description", "", "visibility", "private"))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).path("id").asText());
    }
}
