package com.skillmesh.api.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.support.DatabaseCleaner;
import java.util.Map;
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

/**
 * 成员管理：列、改角色、移除（设计文档 §10 团队管理）。
 *
 * 越权那几条走服务层：测试客户端只有一个真实令牌，
 * 换个身份只能自己造 CurrentUser（同 OrgVisibilityTest 的做法）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberManagementTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;
    @Autowired private OrgService orgs;
    @Autowired private JdbcTemplate jdbc;

    private String auth;
    private CurrentUser owner;

    @BeforeEach
    void 准备() throws Exception {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
        owner = currentUser();
    }

    @Test
    void 成员列表带上显示名和角色() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");
        CurrentUser member = stranger();
        orgs.upsertTeamMember(owner, teamId, member.id(), "member");

        mockMvc.perform(get("/api/v1/organizations/{id}/teams/{tid}/members", orgId, teamId)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].user_id").value(member.id().toString()))
            .andExpect(jsonPath("$[0].display_name").value("Stranger"))
            .andExpect(jsonPath("$[0].role").value("member"))
            .andExpect(jsonPath("$[0].joined_at").exists());
    }

    @Test
    void 组织成员列表() throws Exception {
        UUID orgId = createOrganization("acme");
        mockMvc.perform(get("/api/v1/organizations/{id}/members", orgId)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].user_id").value(owner.id().toString()))
            .andExpect(jsonPath("$[0].role").value("owner"));
    }

    @Test
    void 改角色与移除成员() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");
        CurrentUser member = stranger();
        orgs.upsertTeamMember(owner, teamId, member.id(), "member");

        mockMvc.perform(patch("/api/v1/organizations/{id}/teams/{tid}/members/{uid}",
                orgId, teamId, member.id())
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"publisher\"}"))
            .andExpect(status().isNoContent());
        assertThat(orgs.teamMembers(teamId)).anyMatch(m -> "publisher".equals(m.role()));

        mockMvc.perform(delete("/api/v1/organizations/{id}/teams/{tid}/members/{uid}",
                orgId, teamId, member.id())
                .header("Authorization", auth))
            .andExpect(status().isNoContent());
        assertThat(orgs.teamMembers(teamId)).isEmpty();
    }

    @Test
    void 不能改自己的组织角色() throws Exception {
        UUID orgId = createOrganization("acme");
        mockMvc.perform(patch("/api/v1/organizations/{id}/members/{uid}", orgId, owner.id())
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"member\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 最后一个owner不能被降级() {
        UUID orgId = createOrganizationQuietly("acme");
        CurrentUser admin = stranger();
        orgs.addOrgMember(owner, orgId, admin.id(), "admin");

        // 组织里就 owner 一个 owner，admin 把他降级，组织就永久没有 owner 了
        assertThatThrownBy(() -> orgs.updateOrgRole(admin, orgId, owner.id(), "admin"))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.status()).isEqualTo(400);
                assertThat(ex.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
            });
        assertThat(orgs.orgMembers(orgId))
            .anyMatch(m -> owner.id().equals(m.userId()) && "owner".equals(m.role()));
    }

    @Test
    void 加成员时也不能把最后一个owner降级() {
        UUID orgId = createOrganizationQuietly("acme");
        CurrentUser admin = stranger();
        orgs.addOrgMember(owner, orgId, admin.id(), "admin");

        assertThatThrownBy(() -> orgs.addOrgMember(admin, orgId, owner.id(), "member"))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.status()).isEqualTo(400);
                assertThat(ex.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
            });
        assertThat(orgs.orgMembers(orgId))
            .anyMatch(m -> owner.id().equals(m.userId()) && "owner".equals(m.role()));
    }

    @Test
    void 还有别的owner时可以降掉一个() {
        UUID orgId = createOrganizationQuietly("acme");
        CurrentUser second = stranger();
        orgs.addOrgMember(owner, orgId, second.id(), "owner");

        orgs.updateOrgRole(owner, orgId, second.id(), "admin");
        assertThat(orgs.orgMembers(orgId))
            .anyMatch(m -> second.id().equals(m.userId()) && "admin".equals(m.role()));
    }

    @Test
    void 普通成员不能改组织角色() {
        UUID orgId = createOrganizationQuietly("acme");
        CurrentUser plain = stranger();
        orgs.addOrgMember(owner, orgId, plain.id(), "member");

        assertThatThrownBy(() -> orgs.updateOrgRole(plain, orgId, plain.id(), "admin"))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.status()).isEqualTo(403);
                assertThat(ex.code()).isEqualTo(ErrorCodes.FORBIDDEN);
            });
    }

    @Test
    void admin不能授予owner() {
        UUID orgId = createOrganizationQuietly("acme");
        CurrentUser admin = stranger();
        orgs.addOrgMember(owner, orgId, admin.id(), "admin");

        assertThatThrownBy(() -> orgs.updateOrgRole(admin, orgId, admin.id(), "owner"))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.status()).isEqualTo(403);
                assertThat(ex.code()).isEqualTo(ErrorCodes.FORBIDDEN);
            });
    }

    @Test
    void 非管理员不能改团队角色也不能踢人() {
        UUID orgId = createOrganizationQuietly("acme");
        UUID teamId = createTeamQuietly(orgId, "platform");
        CurrentUser plain = stranger();
        orgs.upsertTeamMember(owner, teamId, plain.id(), "member");

        assertThatThrownBy(() -> orgs.updateTeamRole(plain, teamId, plain.id(), "publisher"))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.status()).isEqualTo(403);
                assertThat(ex.code()).isEqualTo(ErrorCodes.FORBIDDEN);
            });
        assertThatThrownBy(() -> orgs.removeTeamMember(plain, teamId, plain.id()))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.status()).isEqualTo(403);
                assertThat(ex.code()).isEqualTo(ErrorCodes.FORBIDDEN);
            });
    }

    @Test
    void 非法角色被挡在门外() throws Exception {
        UUID orgId = createOrganization("acme");
        CurrentUser target = stranger();
        orgs.addOrgMember(owner, orgId, target.id(), "member");

        mockMvc.perform(patch("/api/v1/organizations/{id}/members/{uid}", orgId, target.id())
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"superuser\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private CurrentUser currentUser() throws Exception {
        String body = mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
            .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).path("user").path("id").asText());
        return new CurrentUser(id, "owner", "Owner", "owner@example.com");
    }

    /** 造一个真实用户行——成员表对 users 有外键，光给个 UUID 插不进去。 */
    private CurrentUser stranger() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, oidc_issuer, oidc_subject, display_name, status) "
            + "values (?, 'https://kc', ?, 'Stranger', 'active')", id, "stranger-" + id);
        return new CurrentUser(id, "stranger-" + id, "Stranger", null);
    }

    private UUID createOrganization(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/organizations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", slug, "name", slug))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("id").asText());
    }

    /** 越权那几条只关心服务端行为，不走 HTTP，建组织直接走服务层。 */
    private UUID createOrganizationQuietly(String slug) {
        return orgs.createOrganization(owner, slug, slug).getId();
    }

    private UUID createTeam(UUID orgId, String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/organizations/{id}/teams", orgId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", slug, "name", slug))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("id").asText());
    }

    private UUID createTeamQuietly(UUID orgId, String slug) {
        return orgs.createTeam(owner, orgId, slug, slug, null).getId();
    }
}
