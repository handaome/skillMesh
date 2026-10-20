package com.skillmesh.api.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.support.DatabaseCleaner;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 邀请流：创建 → 公开查看 → 接受 → 幂等 → 撤销（设计文档 §10 团队管理）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvitationFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;
    @Autowired private InvitationService invitations;
    @Autowired private JdbcTemplate jdbc;

    private String auth;

    @BeforeEach
    void 准备() throws Exception {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
    }

    @Test
    void 管理员能建邀请且库里只存哈希() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");

        String body = mockMvc.perform(post("/api/v1/organizations/{id}/invitations", orgId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "team_id", teamId.toString(), "org_role", "member", "team_role", "member"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token").exists())
            .andExpect(jsonPath("$.invite_url").exists())
            .andExpect(jsonPath("$.organization_slug").value("acme"))
            .andExpect(jsonPath("$.team_slug").value("platform"))
            .andReturn().getResponse().getContentAsString();
        String token = json.readTree(body).path("token").asText();
        assertThat(token.length()).isGreaterThanOrEqualTo(40);

        // 库里存的是哈希，不是明文
        String stored = jdbc.queryForObject(
            "select token_hash from org_invitations limit 1", String.class);
        assertThat(stored).isNotEqualTo(token);
        assertThat(stored).hasSize(64);
    }

    @Test
    void 公开端点不用登录就能看邀请摘要() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");
        String token = createInvitation(orgId, teamId);

        mockMvc.perform(get("/api/v1/invitations/{token}", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.organization_name").value("acme"))
            .andExpect(jsonPath("$.team_name").value("platform"))
            .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void 接受邀请后落成员关系且可重复接受() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");
        String token = createInvitation(orgId, teamId);

        mockMvc.perform(post("/api/v1/invitations/{token}/accept", token)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.org_role").value("member"))
            .andExpect(jsonPath("$.team_id").value(teamId.toString()));

        // 重复接受不报错
        mockMvc.perform(post("/api/v1/invitations/{token}/accept", token)
                .header("Authorization", auth))
            .andExpect(status().isOk());
    }

    @Test
    void 不存在的邀请是404() throws Exception {
        mockMvc.perform(get("/api/v1/invitations/{token}", "no-such-token"))
            .andExpect(status().isNotFound());
    }

    @Test
    void 撤销后不能接受() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");
        String token = createInvitation(orgId, teamId);

        mockMvc.perform(delete("/api/v1/organizations/{id}/invitations/{iid}", orgId, invitationId(token))
                .header("Authorization", auth))
            .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/invitations/{token}/accept", token)
                .header("Authorization", auth))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
    }

    @Test
    void 非管理员不能建邀请() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");
        // 权限要在服务层断言：HTTP 层只有一个真实令牌，造不出「非属主」的身份
        assertThatThrownBy(() -> invitations.create(stranger(), orgId, teamId,
            "member", "member", null))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("权限不足");
    }

    /** token_hash 是 UNIQUE——哈希撞了得让数据库拦下来，不能存两条一样的凭据。 */
    @Test
    void 重复的token哈希被数据库拒绝() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");
        createInvitation(orgId, teamId);
        String hash = jdbc.queryForObject(
            "select token_hash from org_invitations limit 1", String.class);

        assertThatThrownBy(() -> jdbc.update(
            "insert into org_invitations (id, organization_id, org_role, team_role, "
                + "token_hash, status, created_at, expires_at) "
                + "values (?, ?, 'member', 'member', ?, 'pending', now(), now() + interval '7 days')",
            UUID.randomUUID(), orgId, hash))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private String createInvitation(UUID orgId, UUID teamId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/organizations/{id}/invitations", orgId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "team_id", teamId.toString(), "org_role", "member", "team_role", "member"))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("token").asText();
    }

    private UUID invitationId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/v1/invitations/{token}", token))
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("id").asText());
    }

    private UUID createOrganization(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/organizations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", slug, "name", slug))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("id").asText());
    }

    private UUID createTeam(UUID orgId, String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/organizations/{id}/teams", orgId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", slug, "name", slug))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("id").asText());
    }

    /** 造一个真实用户行——成员表对 users 有外键，光给个 UUID 插不进去。 */
    private CurrentUser stranger() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, oidc_issuer, oidc_subject, display_name, status) "
            + "values (?, 'https://kc', ?, 'Stranger', 'active')", id, "stranger-" + id);
        return new CurrentUser(id, "stranger-" + id, "Stranger", null);
    }
}
