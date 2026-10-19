package com.skillmesh.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 制品签名与验签（设计文档 §10 制品完整性）。
 *
 * 密钥在这个测试进程里现生成，只通过 @DynamicPropertySource 注入 Spring 上下文，
 * 不落配置文件、不进日志。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SigningTest {

    private static final String KEY_ID = "test-signing-key";
    private static final KeyPair KEY_PAIR = generateKeyPair();

    @DynamicPropertySource
    static void signingProperties(DynamicPropertyRegistry registry) {
        registry.add("skillmesh.signing.private-key", () ->
            Base64.getEncoder().encodeToString(KEY_PAIR.getPrivate().getEncoded()));
        registry.add("skillmesh.signing.key-id", () -> KEY_ID);
        // 客户端要的是原始 32 字节公钥：X509 编码的最后 32 字节就是它
        byte[] encoded = KEY_PAIR.getPublic().getEncoded();
        byte[] raw = java.util.Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        registry.add("skillmesh.signing.public-key", () -> Base64.getEncoder().encodeToString(raw));
    }

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
    void 上传后制品带可验的签名() throws Exception {
        UUID skillId = createSkill();
        byte[] zip = TestArtifacts.zipOf("SKILL.md", "# Signed\n");
        publish(skillId, "1.0.0", zip);

        String body = mockMvc.perform(get("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].artifact.signature").isNotEmpty())
            .andExpect(jsonPath("$.items[0].artifact.signature_key_id").value(KEY_ID))
            .andReturn().getResponse().getContentAsString();

        var artifact = json.readTree(body).path("items").get(0).path("artifact");
        String digest = artifact.path("content_digest").asText();
        String signature = artifact.path("signature").asText();
        assertThat(digest).isNotEmpty();

        // 用公布的公钥验一遍：签名应该正好覆盖「key_id + 内容摘要」
        String publicKey = mockMvc.perform(get("/api/v1/keys/verify").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.key_id").value(KEY_ID))
            .andReturn().getResponse().getContentAsString();
        // 公布出去的必须是原始 32 字节：客户端的 crypto/ed25519 直接拿这个验
        byte[] encoded = KEY_PAIR.getPublic().getEncoded();
        byte[] raw = java.util.Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        assertThat(json.readTree(publicKey).path("public_key").asText())
            .isEqualTo(Base64.getEncoder().encodeToString(raw));

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KEY_PAIR.getPublic());
        verifier.update((KEY_ID + "\n" + digest).getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(signature)))
            .as("制品签名应该能验过").isTrue();
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("生成测试密钥失败", e);
        }
    }

    private UUID createSkill() throws Exception {
        String resp = mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", "signed", "name", "signed",
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
