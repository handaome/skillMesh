package com.skillmesh.api.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * 并发发版不能把 latest_version 弄丢（详细设计 §10.1）。
 *
 * 两个发布各读各的 latest_version，后写的把先写的顶掉——latest 退回旧版本，
 * 展示层说的「最新版本」从此是一版旧的。发版要锁住 skills 行。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConcurrentPublishTest {

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
     * 两个并发发布，latest_version 要是版本号更大的那个。
     *
     * 一版 1.0.1 一版 1.0.2 同时发：不加锁的话，谁最后写谁赢——
     * 1.0.1 后写就把 latest 顶回去了。加了锁（先版本行、后 Skill 行），
     * 两个发布串行化，latest 只往新版本走。
     */
    @Test
    void 并发发版不会把latest_version顶回旧版本() throws Exception {
        UUID skillId = createSkill("concurrent-publish");

        // 两版的会话先开好，PUT 也先做完——并发的是收尾那一步。
        String sessionLow = openSession(skillId, "1.0.1", "# Low\n");
        String sessionHigh = openSession(skillId, "1.0.2", "# High\n");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> low = pool.submit(() -> complete(sessionLow, start));
            Future<Integer> high = pool.submit(() -> complete(sessionHigh, start));
            start.countDown();
            // 两版都得发成功：并发不该把谁挡掉。
            assertThat(List.of(low.get(60, TimeUnit.SECONDS), high.get(60, TimeUnit.SECONDS)))
                .as("两版都发布成功").containsExactlyInAnyOrder(202, 202);
        } finally {
            pool.shutdownNow();
        }

        // latest 是版本号大的那版，不是谁最后写谁赢。
        String latest = json.readTree(mockMvc.perform(get("/api/v1/skills/{id}", skillId)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString())
            .path("latest_version").asText();
        assertThat(latest).as("latest_version 要是更大的那版").isEqualTo("1.0.2");
    }

    /** 建版本并把包传上去，返回上传会话 id。 */
    private String openSession(UUID skillId, String version, String content) throws Exception {
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
        return node.path("upload").path("session_id").asText();
    }

    /** 把包传到对象存储的直传地址。 */
    private void putToStorage(String url, byte[] data) throws Exception {
        HttpResponse<String> put = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(put.statusCode()).isEqualTo(200);
    }

    /** 收尾上传，返回 HTTP 状态码。 */
    private int complete(String sessionId, CountDownLatch start) {
        try {
            start.await();
            return mockMvc.perform(post("/api/v1/uploads/{id}/complete", sessionId)
                    .header("Authorization", auth))
                .andReturn().getResponse().getStatus();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            return 0;
        }
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
