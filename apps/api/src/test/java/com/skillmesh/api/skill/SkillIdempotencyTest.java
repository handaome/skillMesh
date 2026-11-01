package com.skillmesh.api.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 幂等键走通 HTTP 这一段（详细设计 §2.1、§4.3 API-001）。
 *
 * 同一个键重试：服务端认得出这是同一次请求，把上次的响应原样还回来，
 * 不再建一份。CLI 那边按「作用域 + 内容摘要 + 请求体」算键（见
 * {@code apps/cli/internal/apiclient/publish.go}），这里验的是服务端这一半：
 * 键从请求头进来，重试不重复建资源。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SkillIdempotencyTest {

    private static final String ISSUER = "https://probe.local";
    private static final String SLUG = "probe-idem";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    private String subject;

    @BeforeEach
    void setUp() {
        subject = "probe-idem-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        // 顺序有讲究：skills 指着 users（RESTRICT），得先删 Skill 再删人。
        jdbc.update("delete from audit_events where target_id in "
            + "(select id::text from skills where slug = ?)", SLUG);
        jdbc.update("delete from audit_events where actor_user_id in "
            + "(select id from users where oidc_subject = ?)", subject);
        jdbc.update("delete from skills where slug = ?", SLUG);
        // 幂等键行跟着人一起删（ON DELETE CASCADE）。
        jdbc.update("delete from users where oidc_subject = ?", subject);
    }

    @Test
    void 同一个键重试不会建出第二个Skill() throws Exception {
        String key = "idem-" + UUID.randomUUID();
        String body = """
            {"slug":"probe-idem","name":"探针","description":"幂等键探针","visibility":"private"}
            """;

        JsonNode first = create(key, body);
        JsonNode retry = create(key, body);

        assertThat(retry.path("id").asText()).isEqualTo(first.path("id").asText());
        // 库里只该有一条：重试是把上次的响应还回来，不是又建一个。
        assertThat(jdbc.queryForObject("select count(*) from skills where slug = ?",
            Integer.class, SLUG)).isEqualTo(1);
        // 键表里也只记了一次。
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys where idem_key = ?",
            Integer.class, key)).isEqualTo(1);
    }

    /** 带同一个幂等键建一次 Skill，返回响应体。 */
    private JsonNode create(String key, String body) throws Exception {
        String response = mockMvc.perform(post("/api/v1/skills")
                .with(jwt().jwt(token -> token.issuer(ISSUER).subject(subject)))
                .header("Idempotency-Key", key)
                .contentType(APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }
}
