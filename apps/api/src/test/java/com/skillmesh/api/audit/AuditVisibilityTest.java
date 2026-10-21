package com.skillmesh.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.org.OrgService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 审计流水的可见范围（设计文档 §10）。
 *
 * 可见性判定只有一处来源：SkillService.canView。审计查询是把它翻成 SQL，
 * 这个类盯住翻译后的语义没走样——团队成员看得见团队 Skill 的流水，
 * 外人看不见，私有 Skill 只有属主看得见。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditVisibilityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;
    @Autowired private AuditService audit;
    @Autowired private OrgService orgs;
    @Autowired private SkillService skills;
    @Autowired private JdbcTemplate jdbc;

    private CurrentUser owner;

    @BeforeEach
    void 准备() throws Exception {
        cleaner.clean();
        owner = currentUser();
    }

    @Test
    void 团队Skill的流水对团队成员可见对外人不可见() {
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = skills.create(owner, new CreateSkillRequest("runbook", "Runbook", "",
            "team", orgId, teamId, List.of(), Map.of(), null)).id();
        audit.record(owner, "skill.publish", "skill", skillId, "success", Map.of());

        CurrentUser member = stranger();
        orgs.addTeamMember(owner, teamId, member.id(), "member");
        CurrentUser outsider = stranger();

        assertThat(audit.recentVisible(owner, null, 200)).as("属主看得见").isNotEmpty();
        assertThat(audit.recentVisible(member, null, 200)).as("团队成员看得见").isNotEmpty();
        assertThat(audit.recentVisible(outsider, null, 200)).as("外人看不见团队流水").isEmpty();
    }

    @Test
    void 私有Skill的流水只有属主看得见() {
        UUID skillId = skills.create(owner, new CreateSkillRequest("secret", "Secret", "",
            "private", null, null, List.of(), Map.of(), null)).id();
        audit.record(owner, "skill.publish", "skill", skillId, "success", Map.of());

        assertThat(audit.recentVisible(owner, null, 200)).isNotEmpty();
        assertThat(audit.recentVisible(stranger(), null, 200)).as("别人的私有 Skill 看不见").isEmpty();
    }

    @Test
    void 按动作过滤只返回该动作() {
        UUID skillId = skills.create(owner, new CreateSkillRequest("mixed", "Mixed", "",
            "private", null, null, List.of(), Map.of(), null)).id();
        audit.record(owner, "skill.publish", "skill", skillId, "success", Map.of());
        audit.record(owner, "version.revoke", "skill", skillId, "success", Map.of());

        List<AuditEventEntity> events = audit.recentVisible(owner, "version.revoke", 200);
        assertThat(events).isNotEmpty();
        assertThat(events).allMatch(event -> "version.revoke".equals(event.getAction()));
    }

    /** 非 skill 域的事件不进这条流水。 */
    @Test
    void 只看skill域的事件() {
        audit.record(owner, "organization.create", "organization", UUID.randomUUID(),
            "success", Map.of());
        assertThat(audit.recentVisible(owner, null, 200)).isEmpty();
    }

    /**
     * 客户端上报审计事件时，target 得是上报者看得见的 Skill。
     *
     * 原来 target 完全由客户端给：任何登录用户都能往别人的 Skill 流水里塞
     * 「安装完成」这类条目，details 内容也由上报者指定，审计台分不出真假。
     */
    @Test
    void 上报审计事件不能指向看不见的Skill() throws Exception {
        UUID skillId = skills.create(stranger(), new CreateSkillRequest("secret", "Secret", "",
            "private", null, null, List.of(), Map.of(), null)).id();

        mockMvc.perform(post("/api/v1/audit/events")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "action", "install.completed", "target_type", "skill",
                    "target_id", skillId.toString()))))
            .andExpect(status().isForbidden());
    }

    /** 看得见的照常能报。 */
    @Test
    void 上报审计事件可以指向自己看得见的Skill() throws Exception {
        UUID skillId = skills.create(owner, new CreateSkillRequest("mine", "Mine", "",
            "private", null, null, List.of(), Map.of(), null)).id();

        mockMvc.perform(post("/api/v1/audit/events")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "action", "install.completed", "target_type", "skill",
                    "target_id", skillId.toString()))))
            .andExpect(status().isCreated());
    }
    private CurrentUser currentUser() throws Exception {
        String body = mockMvc.perform(get("/api/v1/me")
                .header("Authorization", "Bearer " + tokens.accessToken()))
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
}
