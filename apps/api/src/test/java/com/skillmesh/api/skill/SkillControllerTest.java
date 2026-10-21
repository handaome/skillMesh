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
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.org.OrgService;
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
    @Autowired private OrgService orgs;

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

    /**
     * 组织内的 slug 唯一，团队之间也共用这一个命名空间。
     *
     * 团队可见的 Skill 你可能根本看不见（不是团队成员），
     * 再建一个同名的必须回 409，而不是撞唯一索引变成 500。
     */
    @Test
    void 组织内重名slug返回409() throws Exception {
        CurrentUser user = currentUser();
        UUID orgId = orgs.createOrganization(user, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(user, orgId, "platform", "Platform", null).getId();
        String body = """
            {"slug":"dup-team","name":"Dup","description":"","visibility":"team",
             "organization_id":"%s","owner_team_id":"%s"}""".formatted(orgId, teamId);

        mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SLUG_TAKEN"));
    }

    /**
     * 团队之间共用一个命名空间：同一组织里换个团队建同名 Skill 也得 409。
     *
     * 原来那条用例两次 POST 用的是同一个 body（同一个团队），
     * 只能证明「同组织同团队重名被拒」——把预检改成按
     * (organization_id, owner_team_id, slug) 查，它照样全绿，
     * 而换团队时唯一索引会拦下并变成 500。
     */
    @Test
    void 同组织不同团队重名也是409() throws Exception {
        CurrentUser user = currentUser();
        UUID orgId = orgs.createOrganization(user, "acme2", "Acme2").getId();
        UUID teamA = orgs.createTeam(user, orgId, "team-a", "Team A", null).getId();
        UUID teamB = orgs.createTeam(user, orgId, "team-b", "Team B", null).getId();

        mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"slug":"cross-team","name":"X","description":"","visibility":"team",
                     "organization_id":"%s","owner_team_id":"%s"}""".formatted(orgId, teamA)))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"slug":"cross-team","name":"X","description":"","visibility":"team",
                     "organization_id":"%s","owner_team_id":"%s"}""".formatted(orgId, teamB)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SLUG_TAKEN"));
    }

    /**
     * 个人空间与组织空间是两套命名空间：自己有个人 demo 时，
     * 在组织里建 demo 不该被误挡——唯一索引 uq_skills_org_slug 只约束组织内。
     */
    @Test
    void 个人空间同名不影响在组织里建() throws Exception {
        CurrentUser user = currentUser();
        UUID orgId = orgs.createOrganization(user, "acme3", "Acme3").getId();
        UUID teamId = orgs.createTeam(user, orgId, "t", "T", null).getId();

        mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"slug":"same-name","name":"X","description":"","visibility":"private"}"""))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"slug":"same-name","name":"X","description":"","visibility":"team",
                     "organization_id":"%s","owner_team_id":"%s"}""".formatted(orgId, teamId)))
            .andExpect(status().isCreated());
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
    void 非法ID返回400而不是500() throws Exception {
        // CLI 的 search 曾经只显示截断的 ID，粘回来就是这个错。
        mockMvc.perform(get("/api/v1/skills/{id}", "131b71e7").header("Authorization", auth))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
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

    @Test
    void mine只看自己名下换设备恢复用() throws Exception {
        create("mine-private", "Mine Private", "d", "x");
        UUID othersPublic = create("others-public", "Others Public", "d", "x");
        jdbc.update("update skills set owner_user_id = ?, visibility = 'public' where id = ?",
            otherUserId(), othersPublic);

        // 默认搜索能看到别人的 public
        assertThat(slugsOf(get("/api/v1/skills"))).contains("others-public");
        // mine=true 只留自己的，换设备恢复时不该把别人的东西装回来
        assertThat(slugsOf(get("/api/v1/skills").param("mine", "true")))
            .containsExactly("mine-private");
    }

    private Set<String> slugsOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
                                request) throws Exception {
        String body = mockMvc.perform(request.header("Authorization", auth))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Set<String> slugs = new HashSet<>();
        json.readTree(body).path("items").forEach(n -> slugs.add(n.path("slug").asText()));
        return slugs;
    }

    private UUID otherUserId() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, oidc_issuer, oidc_subject, display_name, status) "
            + "values (?, 'https://kc', ?, 'Other User', 'active')", id, "other-" + id);
        return id;
    }

    /** 拿当前登录用户的身份：建组织、建团队都要用它。 */
    private CurrentUser currentUser() throws Exception {
        String body = mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).path("user").path("id").asText());
        return new CurrentUser(id, "owner", "Owner", "owner@example.com");
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
