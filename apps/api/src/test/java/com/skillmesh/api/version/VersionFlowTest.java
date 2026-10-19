package com.skillmesh.api.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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

/** 版本发布全流程：建版本 → 直传对象存储 → 服务端校验 → 发布 → 下载。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VersionFlowTest {

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
    void 完整发布流程并下载校验摘要() throws Exception {
        UUID skillId = createSkill("demo-skill");
        byte[] zip = zipOf("SKILL.md", "# Demo Skill\n");

        String created = mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(versionBody("1.0.0", zip)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var node = json.readTree(created);
        String uploadUrl = node.path("upload").path("url").asText();
        String sessionId = node.path("upload").path("session_id").asText();

        putToStorage(uploadUrl, zip);

        mockMvc.perform(post("/api/v1/uploads/{id}/complete", sessionId)
                .header("Authorization", auth))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("completed"))
            .andExpect(jsonPath("$.verified_sha256").value(sha256(zip)));

        String versionsJson = mockMvc.perform(get("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].version").value("1.0.0"))
            .andExpect(jsonPath("$.items[0].status").value("published"))
            // manifest 要透出来：客户端靠它判断内容是否真的变了
            .andExpect(jsonPath("$.items[0].manifest[0].path").value("SKILL.md"))
            .andExpect(jsonPath("$.items[0].manifest[0].sha256").value(sha256(zip)))
            .andReturn().getResponse().getContentAsString();
        var version = json.readTree(versionsJson).path("items").get(0);
        String artifactId = version.path("artifact").path("id").asText();
        assertThat(version.path("artifact").path("sha256").asText()).isEqualTo(sha256(zip));

        byte[] downloaded = mockMvc.perform(get("/api/v1/artifacts/{id}/download", artifactId)
                .header("Authorization", auth).param("disposition", "stream"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(zip);
    }

    @Test
    void 摘要不匹配则拒绝发布() throws Exception {
        UUID skillId = createSkill("mismatch");
        byte[] real = zipOf("SKILL.md", "# Real\n");

        String created = mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(versionBodyWithDigest("1.0.0", real, "b".repeat(64))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var node = json.readTree(created);
        putToStorage(node.path("upload").path("url").asText(), real);

        mockMvc.perform(post("/api/v1/uploads/{id}/complete",
                node.path("upload").path("session_id").asText())
                .header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("DIGEST_MISMATCH"));

        // 版本不应被发布
        mockMvc.perform(get("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth))
            .andExpect(jsonPath("$.items[0].status").value("draft"));
    }

    @Test
    void 同版本号二次创建返回409() throws Exception {
        UUID skillId = createSkill("dup-version");
        byte[] zip = zipOf("SKILL.md", "# X\n");
        mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(versionBody("1.0.0", zip)))
            .andExpect(status().isCreated());

        var result = mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(versionBody("1.0.0", zip)))
            .andExpect(status().isConflict())
            .andReturn();
        assertThat(json.readTree(result.getResponse().getContentAsString()).path("code").asText())
            .isEqualTo("VERSION_CONFLICT");
    }

    @Test
    void 同一份内容发成两个版本时仍可下载() throws Exception {
        UUID skillId = createSkill("same-content");
        byte[] zip = zipOf("SKILL.md", "# Same\n");

        String first = publishVersion(skillId, "1.0.0", zip);
        String second = publishVersion(skillId, "1.0.1", zip);
        // 两次内容相同 → sha256 相同 → 共用同一条 artifacts 记录
        assertThat(json.readTree(first).path("artifact").path("id").asText())
            .isEqualTo(json.readTree(second).path("artifact").path("id").asText());

        String artifactId = json.readTree(first).path("artifact").path("id").asText();
        byte[] downloaded = mockMvc.perform(get("/api/v1/artifacts/{id}/download", artifactId)
                .header("Authorization", auth).param("disposition", "stream"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(zip);
    }

    /** 发布一个版本并完成上传，返回版本列表里该版本那一项的 JSON。 */
    private String publishVersion(UUID skillId, String version, byte[] zip) throws Exception {
        String created = mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(versionBody(version, zip)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var node = json.readTree(created);
        putToStorage(node.path("upload").path("url").asText(), zip);
        mockMvc.perform(post("/api/v1/uploads/{id}/complete",
                node.path("upload").path("session_id").asText())
                .header("Authorization", auth))
            .andExpect(status().isAccepted());

        String versionsJson = mockMvc.perform(get("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth))
            .andReturn().getResponse().getContentAsString();
        for (var item : json.readTree(versionsJson).path("items")) {
            if (item.path("version").asText().equals(version)) {
                return item.toString();
            }
        }
        throw new AssertionError("版本列表里找不到 " + version);
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

    private void putToStorage(String url, byte[] data) throws Exception {
        HttpResponse<String> put = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isEqualTo(200);
    }

    private String versionBody(String version, byte[] zip) throws Exception {
        return versionBodyWithDigest(version, zip, sha256(zip));
    }

    private String versionBodyWithDigest(String version, byte[] zip, String digest) throws Exception {
        return json.writeValueAsString(Map.of(
            "version", version, "channel", "stable", "size_bytes", zip.length,
            "sha256", digest, "file_count", 1,
            "manifest", List.of(Map.of(
                "path", "SKILL.md", "sha256", digest, "size_bytes", zip.length))));
    }

    static byte[] zipOf(String name, String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return out.toByteArray();
    }

    static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
