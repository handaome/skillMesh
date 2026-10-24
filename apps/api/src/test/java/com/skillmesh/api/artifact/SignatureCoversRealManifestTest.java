package com.skillmesh.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.common.ContentDigest;
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
 * 签名覆盖的是哪份清单（详细设计 §10 制品完整性 + §13 服务端重算）。
 *
 * 发布方在建版本请求里自填一份清单，那只是他的说法。真包里的文件由服务端重算，
 * 签名、`content_digest`、审核面板列的清单都得对着真包那份——
 * 否则发布方声明「只有 SKILL.md」、真包里塞了别的文件，照发不误，
 * 而审核的人看到的是一份假的文件清单。
 *
 * 这条用例把两件事钉在一起：签名验得过，且它覆盖的是**真包**那份清单。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignatureCoversRealManifestTest {

    private static final String KEY_ID = "manifest-signing-key";
    private static final KeyPair KEY_PAIR = generateKeyPair();

    @DynamicPropertySource
    static void signingProperties(DynamicPropertyRegistry registry) {
        registry.add("skillmesh.signing.private-key", () ->
            Base64.getEncoder().encodeToString(KEY_PAIR.getPrivate().getEncoded()));
        registry.add("skillmesh.signing.key-id", () -> KEY_ID);
        // 客户端要的是原始 32 字节公钥：X509 编码的最后 32 字节就是它。
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

    /**
     * 声明不可信时，签名得覆盖真包那份清单。
     *
     * 声明只说有 SKILL.md，真包里还有 secret.sh。签名的对象必须是两份清单合起来的那份
     * （含 secret.sh），不是声明那份——否则审核的人按签名验的是一份没装 secret.sh 的清单。
     */
    @Test
    void 签名覆盖的是服务端重算的清单() throws Exception {
        UUID skillId = createSkill("manifest-truth");
        byte[] zip = TestArtifacts.zipOf(Map.of("SKILL.md", "# Truth\n", "secret.sh", "echo hi\n"));

        // 声明只填 SKILL.md，且把它的摘要写错——发布方在撒谎。
        String created = mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "version", "1.0.0", "channel", "stable", "size_bytes", zip.length,
                    "sha256", TestArtifacts.sha256(zip), "file_count", 1,
                    "manifest", List.of(Map.of("path", "SKILL.md",
                        "sha256", "0".repeat(64), "size_bytes", 8))))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var node = json.readTree(created);
        putToStorage(node.path("upload").path("url").asText(), zip);
        mockMvc.perform(post("/api/v1/uploads/{id}/complete",
                node.path("upload").path("session_id").asText())
                .header("Authorization", auth))
            .andExpect(status().isAccepted());

        String body = mockMvc.perform(get("/api/v1/skills/{id}/versions/{v}", skillId, "1.0.0")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        var version = json.readTree(body);

        // 清单是服务端从真包算的：两个文件，且 secret.sh 在里面。
        var manifest = version.path("manifest");
        assertThat(manifest).as("清单以真包为准").hasSize(2);
        assertThat(manifest.toString()).contains("secret.sh");

        // content_digest 必须是这份清单的摘要——自己算一遍对得上才算数。
        // 光断言「非空」不够：服务端若改成摘要声明那份，这里照样过。
        String digest = version.path("artifact").path("content_digest").asText();
        assertThat(digest).as("content_digest 摘要的是重算后的清单")
            .isEqualTo(ContentDigest.of(toManifestList(manifest)));

        // 签名覆盖的正是这份 digest：拿公布的公钥验一遍。
        String signature = version.path("artifact").path("signature").asText();
        assertThat(signature).as("制品带签名").isNotEmpty();
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KEY_PAIR.getPublic());
        verifier.update((KEY_ID + "\n" + digest).getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(signature)))
            .as("签名应该正好覆盖 content_digest").isTrue();
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("生成测试密钥失败", e);
        }
    }

    /** 响应里的清单转成 ContentDigest 认得的形状。 */
    private static List<Map<String, Object>> toManifestList(com.fasterxml.jackson.databind.JsonNode manifest) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        manifest.forEach(item -> out.add(Map.of(
            "path", item.path("path").asText(),
            "sha256", item.path("sha256").asText())));
        return out;
    }

    private void putToStorage(String url, byte[] data) throws Exception {
        HttpResponse<String> put = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(put.statusCode()).isEqualTo(200);
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
}
