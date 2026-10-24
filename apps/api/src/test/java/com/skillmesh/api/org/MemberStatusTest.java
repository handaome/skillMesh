package com.skillmesh.api.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.support.DatabaseCleaner;
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
 * 组织成员的账号状态（users.status 那一半）。
 *
 * 状态字段本来没人能改——「禁用/启用用户」这个动作本身没有。
 * 现在组织 owner/admin 能禁用本组织的成员，禁用立刻生效。
 *
 * 测试客户端只有一个真实令牌，换个身份只能自己造 CurrentUser
 * （同 MemberManagementTest 的做法）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberStatusTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;
    @Autowired private OrgService orgs;
    @Autowired private JdbcTemplate jdbc;

    private String auth;
    private CurrentUser caller;

    @BeforeEach
    void 准备() throws Exception {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
        caller = currentUser();
    }

    /** 组织 admin 能禁用本组织的成员，禁用写进 users 表。 */
    @Test
    void 组织admin能禁用成员() throws Exception {
        CurrentUser orgOwner = stranger();
        UUID orgId = orgs.createOrganization(orgOwner, "acme", "Acme").getId();
        orgs.addOrgMember(orgOwner, orgId, caller.id(), "admin");
        CurrentUser member = stranger();
        orgs.addOrgMember(orgOwner, orgId, member.id(), "member");

        mockMvc.perform(patch("/api/v1/organizations/{id}/members/{uid}", orgId, member.id())
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"disabled\"}"))
            .andExpect(status().isNoContent());

        assertThat(statusInDb(member.id())).as("账号状态写到 users 表").isEqualTo("disabled");
        // 花名册带上状态，前端才知道谁被禁用了。
        mockMvc.perform(get("/api/v1/organizations/{id}/members", orgId)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"status\":\"disabled\"")));
    }

    /** 禁用立刻生效：被禁用的账号令牌立刻失效。 */
    @Test
    void 禁用后令牌立刻失效() throws Exception {
        CurrentUser orgOwner = stranger();
        UUID orgId = orgs.createOrganization(orgOwner, "acme", "Acme").getId();
        orgs.addOrgMember(orgOwner, orgId, caller.id(), "member");
        // owner 禁 caller：caller 不是 owner，禁得动。
        orgs.updateOrgMember(orgOwner, orgId, caller.id(), null, "disabled");

        mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(ErrorCodes.FORBIDDEN));
    }

    /** 不是管理员的人不能改别人的账号状态。 */
    @Test
    void 非管理员不能禁用成员() {
        CurrentUser orgOwner = stranger();
        UUID orgId = orgs.createOrganization(orgOwner, "acme", "Acme").getId();
        CurrentUser member = stranger();
        orgs.addOrgMember(orgOwner, orgId, member.id(), "member");
        CurrentUser other = stranger();
        orgs.addOrgMember(orgOwner, orgId, other.id(), "member");

        assertThatThrownBy(() -> orgs.updateOrgMember(other, orgId, member.id(), null, "disabled"))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.status()).isEqualTo(403);
                assertThat(ex.code()).isEqualTo(ErrorCodes.FORBIDDEN);
            });
    }

    /** 不能禁用组织 owner——owner 是组织的最后一道保险。 */
    @Test
    void 不能禁用组织owner() {
        CurrentUser orgOwner = stranger();
        UUID orgId = orgs.createOrganization(orgOwner, "acme", "Acme").getId();
        orgs.addOrgMember(orgOwner, orgId, caller.id(), "admin");
        CurrentUser other = stranger();
        orgs.addOrgMember(orgOwner, orgId, other.id(), "owner");

        assertThatThrownBy(() -> orgs.updateOrgMember(caller, orgId, other.id(), null, "disabled"))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.status()).isEqualTo(403);
                assertThat(ex.code()).isEqualTo(ErrorCodes.FORBIDDEN);
            });
    }

    /** 也不能禁用自己的账号。 */
    @Test
    void 不能禁用自己() {
        CurrentUser orgOwner = stranger();
        UUID orgId = orgs.createOrganization(orgOwner, "acme", "Acme").getId();
        orgs.addOrgMember(orgOwner, orgId, caller.id(), "admin");

        assertThatThrownBy(() -> orgs.updateOrgMember(caller, orgId, caller.id(), null, "disabled"))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.status()).isEqualTo(403);
                assertThat(ex.code()).isEqualTo(ErrorCodes.FORBIDDEN);
            });
    }

    /** 状态只能取 active / disabled——库里还有 pending，但这个接口不开放。 */
    @Test
    void 非法状态被挡() {
        CurrentUser orgOwner = stranger();
        UUID orgId = orgs.createOrganization(orgOwner, "acme", "Acme").getId();
        CurrentUser member = stranger();
        orgs.addOrgMember(orgOwner, orgId, member.id(), "member");

        assertThatThrownBy(() -> orgs.updateOrgMember(orgOwner, orgId, member.id(), null, "pending"))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.status()).isEqualTo(400);
                assertThat(ex.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
            });
    }

    private CurrentUser currentUser() throws Exception {
        String body = mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).path("user").path("id").asText());
        return new CurrentUser(id, "caller", "Caller", null);
    }

    /** 造一个真实用户行——成员表对 users 有外键，光给个 UUID 插不进去。 */
    private CurrentUser stranger() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, oidc_issuer, oidc_subject, display_name, status) "
            + "values (?, 'https://kc', ?, 'Stranger', 'active')", id, "stranger-" + id);
        return new CurrentUser(id, "stranger-" + id, "Stranger", null);
    }

    private String statusInDb(UUID userId) {
        return jdbc.queryForObject("select status from users where id = ?", String.class, userId);
    }
}
