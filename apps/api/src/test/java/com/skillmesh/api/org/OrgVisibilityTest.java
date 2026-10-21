package com.skillmesh.api.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SkillService;
import com.skillmesh.api.skill.dto.CreateSkillRequest;
import com.skillmesh.api.support.DatabaseCleaner;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * 组织、团队与团队可见性（设计文档 §10 团队管理、§11 数据模型）。
 *
 * 关键验收点：非成员看不到团队 Skill，加进团队就能看到
 * （§15.1「无团队权限的用户无法下载团队私有 Skill」）。
 *
 * 第二个人物直接构造 CurrentUser：测试客户端只有一个真实令牌，
 * 而这里要验的正是「同一个人在有没有成员关系时的可见性差异」。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrgVisibilityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;
    @Autowired private OrgService orgs;
    @Autowired private SkillService skills;
    @Autowired private SkillRepository skillRepository;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String auth;

    @BeforeEach
    void 准备() throws Exception {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
    }

    @Test
    void 团队Skill只对团队成员可见() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID platformTeam = createTeam(orgId, "platform");
        UUID skillId = createTeamSkill(orgId, platformTeam, "platform-runbook");

        CurrentUser owner = owner();
        CurrentUser outsider = stranger();

        assertThat(skills.canView(owner, load(skillId))).as("属主看得见").isTrue();
        assertThat(skills.canView(outsider, load(skillId))).as("非成员看不见团队 Skill").isFalse();

        orgs.addTeamMember(owner, platformTeam, outsider.id(), "member");
        assertThat(skills.canView(outsider, load(skillId))).as("加进团队后能看见").isTrue();
    }

    /**
     * 团队可见的内容归团队管：组织里的普通成员看不到别的团队的 Skill。
     *
     * fix6 曾把它放宽成「组织成员也能读」，那等于把「团队可见」变成「组织内可见」——
     * 用户已拍板收窄：只有该团队成员与该组织的 owner/admin 能看。
     */
    @Test
    void 组织普通成员看不见别的团队的Skill() throws Exception {
        UUID orgId = createOrganization("acme");
        createTeam(orgId, "platform");
        UUID otherTeam = createTeam(orgId, "payments");
        UUID skillId = createTeamSkill(orgId, otherTeam, "payments-runbook");

        CurrentUser owner = owner();
        CurrentUser orgOnly = stranger();
        orgs.addOrgMember(owner, orgId, orgOnly.id(), "member");

        assertThat(skills.canView(orgOnly, load(skillId)))
            .as("组织里的普通成员不是该团队成员，看不见团队可见的 Skill").isFalse();
        assertThatThrownBy(() -> skills.get(orgOnly, skillId))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(404));
        assertThat(skills.search(orgOnly, "payments-runbook", null, null, false, 20, null).items())
            .as("组织里的普通成员搜不到别的团队的 Skill").isEmpty();
    }

    /**
     * 组织的 admin（不是该团队成员）看得见团队可见的 Skill。
     *
     * 他没有团队成员行，但按词表是组织管理员——能管团队、能批待审版本，
     * 「能批不能看」说不通。
     */
    @Test
    void 组织admin不是团队成员也能读团队Skill() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");
        UUID skillId = createTeamSkill(orgId, teamId, "platform-runbook");

        CurrentUser admin = stranger();
        orgs.addOrgMember(owner(), orgId, admin.id(), "admin");

        assertThat(orgs.teamMembers(teamId)).as("没有该团队的成员行").isEmpty();
        assertThat(skills.canView(admin, load(skillId))).as("组织 admin 看得见").isTrue();
        assertThat(skills.get(admin, skillId).slug()).as("直读得到").isEqualTo("platform-runbook");
        assertThat(skills.search(admin, "platform-runbook", null, null, false, 20, null).items())
            .as("组织 admin 搜得到").isNotEmpty();
    }

    /**
     * 组织 owner 建了团队、自己没有该团队的成员行（建团队不写成员行）。
     *
     * 他在审核队列里能看见并批准团队 Skill（放行权有组织回落），
     * 却读不到、搜不到、装不了——「能批不能看」说不通。
     */
    @Test
    void 组织owner没有团队成员行也能读团队Skill() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");

        // 团队 Skill 由别人建：这样「属主自己可见」那条规则不掺和，
        // 测的确实是组织回落。
        CurrentUser publisher = stranger();
        orgs.addOrgMember(owner(), orgId, publisher.id(), "publisher");
        UUID skillId = skills.create(publisher, new CreateSkillRequest(
            "platform-runbook", "Platform Runbook", "", "team", orgId, teamId,
            List.of(), Map.of(), null)).id();

        // 前提：owner 只是组织成员，没有该团队的成员行
        assertThat(orgs.teamMembers(teamId)).as("建团队不写成员行").isEmpty();

        mockMvc.perform(get("/api/v1/skills/{id}", skillId).header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.slug").value("platform-runbook"));

        String body = mockMvc.perform(get("/api/v1/skills").param("q", "platform-runbook")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).path("items").toString())
            .as("组织 owner 在搜索里也搜得到").contains("platform-runbook");
    }

    /**
     * 反向：既不是团队成员也不是组织成员的人，团队 Skill 照旧看不见、搜不到。
     * 别把口子开大。
     */
    @Test
    void 外人既看不见也搜不到团队Skill() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");
        UUID skillId = createTeamSkill(orgId, teamId, "platform-runbook");

        CurrentUser outsider = stranger();
        assertThat(skills.canView(outsider, load(skillId))).as("外人看不见").isFalse();
        assertThat(skills.search(outsider, "platform-runbook", null, null, false, 20, null)
            .items()).as("外人搜不到").isEmpty();
    }

    @Test
    void 搜索只返回自己有权看的() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");
        createTeamSkill(orgId, teamId, "visible-to-team");

        // 另一个人建的组织与团队 Skill：我没加入，搜不到
        CurrentUser other = stranger();
        UUID otherOrg = orgs.createOrganization(other, "other-org", "Other Org").getId();
        UUID otherTeam = orgs.createTeam(other, otherOrg, "secret", "Secret", null).getId();
        skills.create(other, new CreateSkillRequest("not-for-me", "Not For Me", "",
            "team", otherOrg, otherTeam, List.of(), Map.of(), null));

        String body = mockMvc.perform(get("/api/v1/skills").param("limit", "50")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String items = json.readTree(body).path("items").toString();
        assertThat(items).contains("visible-to-team").doesNotContain("not-for-me");
    }

    @Test
    void 非成员不能建团队也不能往团队加人() throws Exception {
        UUID orgId = createOrganization("restricted");
        UUID teamId = createTeam(orgId, "platform");
        CurrentUser outsider = stranger();

        assertThatThrownBy(() -> orgs.createTeam(outsider, orgId, "sneaky", "Sneaky", null))
            .isInstanceOf(ApiException.class).hasMessageContaining("权限不足");
        assertThatThrownBy(() -> orgs.addTeamMember(outsider, teamId, UUID.randomUUID(), "member"))
            .isInstanceOf(ApiException.class).hasMessageContaining("管理员");
        assertThatThrownBy(() -> skills.create(outsider, new CreateSkillRequest(
                "nope", "Nope", "", "team", orgId, teamId, List.of(), Map.of(), null)))
            .isInstanceOf(ApiException.class).hasMessageContaining("成员");
    }

    @Test
    void me里能看到自己的组织和角色() throws Exception {
        createOrganization("acme");
        mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.organizations[0].slug").value("acme"))
            .andExpect(jsonPath("$.organizations[0].role").value("owner"));
    }

    private CurrentUser owner() throws Exception {
        String body = mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
            .andExpect(status().isOk())
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

    private SkillEntity load(UUID id) {
        return skillRepository.findById(id).orElseThrow();
    }

    private UUID createOrganization(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/organizations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", slug, "name", slug))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.slug").value(slug))
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

    private UUID createTeamSkill(UUID orgId, UUID teamId, String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "slug", slug,
                    "name", slug,
                    "description", "",
                    "visibility", "team",
                    "organization_id", orgId.toString(),
                    "owner_team_id", teamId.toString()))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("id").asText());
    }
}
