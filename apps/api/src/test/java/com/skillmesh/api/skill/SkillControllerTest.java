package com.skillmesh.api.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.support.DatabaseCleaner;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkillControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private String auth;

    @BeforeEach
    void 准备() throws Exception {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
    }

    @Test
    void 创建Skill返回201与Location() throws Exception {
        mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"slug":"java-debug","name":"Java Debug","description":"d","visibility":"private",
                     "tags":["java"],"metadata":{"x":1}}"""))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location",
                org.hamcrest.Matchers.matchesPattern("/api/v1/skills/[0-9a-f-]+")))
            .andExpect(jsonPath("$.slug").value("java-debug"))
            .andExpect(jsonPath("$.tags[0]").value("java"))
            .andExpect(jsonPath("$.metadata.x").value(1));
    }

    @Test
    void 同用户重复slug返回409() throws Exception {
        String body = """
            {"slug":"dup","name":"Dup","description":"","visibility":"private"}""";
        mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SLUG_TAKEN"));
    }

    @Test
    void 非法slug返回400并带field_errors() throws Exception {
        mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"slug":"Bad Slug!","name":"x","description":"","visibility":"private"}"""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.field_errors[0].field").value("slug"));
    }

    @Test
    void 搜索按关键字与标签过滤() throws Exception {
        create("alpha", "Alpha Skill", "java debugging", "java");
        create("beta", "Beta Skill", "rust stuff", "rust");

        mockMvc.perform(get("/api/v1/skills").header("Authorization", auth).param("q", "alpha"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].slug").value("alpha"))
            .andExpect(jsonPath("$.has_more").value(false));

        mockMvc.perform(get("/api/v1/skills").header("Authorization", auth).param("tag", "rust"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].slug").value("beta"));
    }

    @Test
    void 别人的私有Skill不可见() throws Exception {
        UUID id = create("secret", "Secret", "hidden", "x");
        mockMvc.perform(get("/api/v1/skills/" + id).header("Authorization", auth))
            .andExpect(status().isOk());

        // 把属主换成另一个用户，模拟别人的私有 Skill。
        // 私有资源不可见时按 404 处理，不泄露存在性（详细设计 §2.1）。
        jdbc.update("update skills set owner_user_id = ? where id = ?", otherUserId(), id);
        mockMvc.perform(get("/api/v1/skills/" + id).header("Authorization", auth))
            .andExpect(status().isNotFound());
    }

    private UUID otherUserId() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, oidc_issuer, oidc_subject, display_name, status) "
            + "values (?, 'https://kc', ?, 'Other User', 'active')", id, "other-" + id);
        return id;
    }

    @Test
    void 游标分页不重不漏() throws Exception {
        for (int i = 0; i < 5; i++) {
            create("skill-" + i, "Skill " + i, "d", "x");
        }
        var page1 = json.readTree(mockMvc.perform(get("/api/v1/skills")
                .header("Authorization", auth).param("limit", "2"))
            .andReturn().getResponse().getContentAsString());
        assertThat(page1.path("has_more").asBoolean()).isTrue();

        Set<String> slugs = new HashSet<>();
        page1.path("items").forEach(n -> slugs.add(n.path("slug").asText()));
        String next = page1.path("next_cursor").asText(null);
        while (next != null && !next.isBlank()) {
            var page = json.readTree(mockMvc.perform(get("/api/v1/skills")
                    .header("Authorization", auth).param("limit", "2").param("cursor", next))
                .andReturn().getResponse().getContentAsString());
            page.path("items").forEach(n -> slugs.add(n.path("slug").asText()));
            next = page.path("has_more").asBoolean() ? page.path("next_cursor").asText(null) : null;
        }
        assertThat(slugs).hasSize(5);
    }

    private UUID create(String slug, String name, String desc, String tag) throws Exception {
        String body = json.writeValueAsString(Map.of(
            "slug", slug, "name", name, "description", desc,
            "visibility", "private", "tags", java.util.List.of(tag)));
        String resp = mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).path("id").asText());
    }
}
