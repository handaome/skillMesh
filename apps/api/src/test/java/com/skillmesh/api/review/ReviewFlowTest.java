package com.skillmesh.api.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.org.OrgService;
import com.skillmesh.api.org.TeamEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.support.DatabaseCleaner;
import com.skillmesh.api.support.TestArtifacts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * 发布审核流（设计文档 §10.1、§12）。
 *
 * 团队可见的版本发上去停在 pending_review，reviewer 放行才对别人可见；
 * 自己发的自己审要被拒。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;
    @Autowired private OrgService orgs;
    @Autowired private ReviewService reviews;
    @Autowired private SkillRepository skills;
    @Autowired private JdbcTemplate jdbc;

    private String auth;

    @BeforeEach
    void 准备() throws Exception {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
    }

    @Test
    void 团队版本要审核通过才发布() throws Exception {
        CurrentUser publisher = owner();
        UUID orgId = orgs.createOrganization(publisher, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(publisher, orgId, "platform", "Platform", null).getId();
        UUID skillId = createTeamSkill(orgId, teamId, "runbook");

        publish(skillId, "1.0.0");
        assertThat(versionStatus(skillId, "1.0.0"))
            .as("团队版本发上去要停住等人审").isEqualTo("pending_review");

        CurrentUser reviewer = userWithRole(orgId, teamId, "reviewer");
        reviews.approve(reviewer, skillId, "1.0.0", "看起来没问题");

        assertThat(versionStatus(skillId, "1.0.0")).isEqualTo("published");
    }

    @Test
    void 审核人角色不够则拒绝() throws Exception {
        CurrentUser owner = owner();
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = createTeamSkill(orgId, teamId, "runbook");
        publish(skillId, "1.0.0");

        CurrentUser plain = userWithRole(orgId, teamId, "member");
        assertThatThrownBy(() -> reviews.approve(plain, skillId, "1.0.0", ""))
            .isInstanceOf(com.skillmesh.api.common.ApiException.class)
            .hasMessageContaining("reviewer");
        assertThat(versionStatus(skillId, "1.0.0")).isEqualTo("pending_review");
    }

    @Test
    void 审核人不能审自己发的版本() throws Exception {
        CurrentUser owner = owner();
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = createTeamSkill(orgId, teamId, "runbook");
        publish(skillId, "1.0.0");

        // owner 自己是组织 owner，角色够高，但发布人也是自己
        mockMvc.perform(post("/api/v1/skills/{id}/versions/{v}/approve", skillId, "1.0.0")
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void 个人Skill不受审核影响() throws Exception {
        UUID skillId = createSkill("personal");
        publish(skillId, "1.0.0");
        assertThat(versionStatus(skillId, "1.0.0"))
            .as("个人 Skill 扫描通过就发布").isEqualTo("published");
    }

    @Test
    void 组织角色是publisher就能提交审核() throws Exception {
        CurrentUser owner = owner();
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = createTeamSkill(orgId, teamId, "runbook");
        publish(skillId, "1.0.0");
        // 先被打回，提交审核才有意义（被驳回的版本要能重新进队列）
        reviews.reject(owner, skillId, "1.0.0", "打回重改");
        assertThat(versionStatus(skillId, "1.0.0")).isEqualTo("rejected");

        // 接受邀请后就是这个状态：组织角色 publisher，团队角色只是 member。
        // 提交审核的门槛跟 publish 一样只看组织角色，团队角色不该把它挡住。
        CurrentUser publisher = userWithRoles(orgId, teamId, "publisher", "member");
        reviews.submit(publisher, skillId, "1.0.0", "改好了");

        assertThat(versionStatus(skillId, "1.0.0"))
            .as("组织角色够就能把驳回的版本重新送审").isEqualTo("pending_review");
    }

    /**
     * 扫描被拦下的版本不许重新送审。
     *
     * 服务端扫描命中阻断规则就把版本置成 rejected；再 submit 一次就能把它
     * 放回队列，过审就等于把带密制品发出去——服务端扫描那道闸整个绕过去了。
     */
    @Test
    void 扫描被拦截的版本不能送审() throws Exception {
        CurrentUser owner = owner();
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = createTeamSkill(orgId, teamId, "runbook");
        publish(skillId, "1.0.0", "AKIAIOSFODNN7EXAMPLE\n");
        assertThat(versionStatus(skillId, "1.0.0")).isEqualTo("rejected");

        ApiException error = catchThrowableOfType(
            () -> reviews.submit(owner, skillId, "1.0.0", "改好了"), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.status()).isEqualTo(409);
        assertThat(error.getMessage()).contains("扫描");
        assertThat(versionStatus(skillId, "1.0.0"))
            .as("被拦下的版本不能回到待审队列").isEqualTo("rejected");
    }

    /** 没上传过制品的版本不许送审——送审了队列里也是空的。 */
    @Test
    void 没有制品的版本不能送审() throws Exception {
        CurrentUser owner = owner();
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = createTeamSkill(orgId, teamId, "runbook");
        createDraft(skillId, "1.0.0");

        ApiException error = catchThrowableOfType(
            () -> reviews.submit(owner, skillId, "1.0.0", "送审"), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.status()).isEqualTo(409);
        assertThat(error.getMessage()).contains("制品");
    }

    /**
     * 队列里没有制品的版本不许过审。
     *
     * submit 已经被前置条件挡住了，这个状态只能手工造——但它正是 brief 里
     * 说的那条路：过了审 skill.latest_version 就指向一个没有制品的版本。
     */
    @Test
    void 没有制品的版本不能过审() throws Exception {
        CurrentUser owner = owner();
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = createTeamSkill(orgId, teamId, "runbook");
        createDraft(skillId, "1.0.0");
        jdbc.update("update skill_versions set status = 'pending_review' "
            + "where skill_id = ? and version = ?", skillId, "1.0.0");

        CurrentUser reviewer = userWithRole(orgId, teamId, "reviewer");
        ApiException error = catchThrowableOfType(
            () -> reviews.approve(reviewer, skillId, "1.0.0", ""), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.status()).isEqualTo(409);
        assertThat(versionStatus(skillId, "1.0.0"))
            .as("没制品的版本不能被放行").isEqualTo("pending_review");
    }

    /**
     * 同一个人对同一版本送审两次：两次都成功，版本仍在队列里。
     *
     * 送审是幂等的：版本已经在队列里就什么都不用做。
     * 送审也不是审核结论，不占 skill_reviews 的名额（那是审核动作的）。
     */
    @Test
    void 同一个人对同一版本送审两次是幂等的() throws Exception {
        CurrentUser owner = owner();
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = createTeamSkill(orgId, teamId, "runbook");
        publish(skillId, "1.0.0");
        reviews.submit(owner, skillId, "1.0.0", "送审");
        reviews.submit(owner, skillId, "1.0.0", "再送一次");

        assertThat(versionStatus(skillId, "1.0.0"))
            .as("重复送审后版本仍在待审队列里").isEqualTo("pending_review");
    }

    /**
     * 驳回之后提交人必须还能再送审。
     *
     * 送审只是排队，不是审核结论——它不该占 skill_reviews 的名额。
     * 占了名额，提交人被驳回之后就再也送不回去（409 VERSION_CONFLICT），
     * 而同名版本又不能重新建，这一版就永久卡死了。
     */
    @Test
    void 驳回之后还能重新送审() throws Exception {
        CurrentUser owner = owner();
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = createTeamSkill(orgId, teamId, "runbook");
        publish(skillId, "1.0.0");
        reviews.submit(owner, skillId, "1.0.0", "送审");

        CurrentUser reviewer = userWithRole(orgId, teamId, "reviewer");
        reviews.reject(reviewer, skillId, "1.0.0", "打回重改");
        assertThat(versionStatus(skillId, "1.0.0")).isEqualTo("rejected");

        reviews.submit(owner, skillId, "1.0.0", "改好了");

        assertThat(versionStatus(skillId, "1.0.0"))
            .as("驳回之后必须还能重新送审").isEqualTo("pending_review");
    }

    /**
     * 同一个人对同一版本审两次要 409，不是 500。
     *
     * 驳回 → 重新送审 → 同一个人再过审：第二次过审会撞同一条唯一约束。
     */
    @Test
    void 同一个人对同一版本审两次要409() throws Exception {
        CurrentUser owner = owner();
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = createTeamSkill(orgId, teamId, "runbook");
        publish(skillId, "1.0.0");
        CurrentUser reviewer = userWithRole(orgId, teamId, "reviewer");
        reviews.reject(reviewer, skillId, "1.0.0", "打回重改");
        reviews.submit(owner, skillId, "1.0.0", "改好了");

        ApiException error = catchThrowableOfType(
            () -> reviews.approve(reviewer, skillId, "1.0.0", ""), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.status()).isEqualTo(409);
        assertThat(versionStatus(skillId, "1.0.0"))
            .as("重复过审不该把版本放出去").isEqualTo("pending_review");
    }

    /**
     * 过审旧版本不能把 latest_version 顶回去。
     *
     * 先批 v1.1.0 再批 v1.0.1：latest 应当还是 v1.1.0，
     * 展示层的「最新版本」不该变成旧的一版。
     */
    @Test
    void 过审旧版本不会把latest顶回去() throws Exception {
        CurrentUser owner = owner();
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = createTeamSkill(orgId, teamId, "runbook");
        publish(skillId, "1.0.1");
        publish(skillId, "1.1.0");

        CurrentUser reviewer = userWithRole(orgId, teamId, "reviewer");
        reviews.approve(reviewer, skillId, "1.1.0", "先批新的");
        reviews.approve(reviewer, skillId, "1.0.1", "再批旧的");

        assertThat(latestVersion(skillId)).isEqualTo("1.1.0");
    }

    private String latestVersion(UUID skillId) throws Exception {
        String body = mockMvc.perform(get("/api/v1/skills/{id}", skillId)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("latest_version").asText();
    }

    private String versionStatus(UUID skillId, String version) throws Exception {
        String body = mockMvc.perform(get("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        for (var item : json.readTree(body).path("items")) {
            if (item.path("version").asText().equals(version)) {
                return item.path("status").asText();
            }
        }
        return "missing";
    }

    private CurrentUser owner() throws Exception {
        String body = mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).path("user").path("id").asText());
        return new CurrentUser(id, "owner", "Owner", "owner@example.com");
    }

    /** 造一个真实用户行并给它一个组织身份。 */
    private CurrentUser userWithRole(UUID orgId, UUID teamId, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, oidc_issuer, oidc_subject, display_name, status) "
            + "values (?, 'https://kc', ?, 'Reviewer', 'active')", id, "rev-" + id);
        if (teamId != null) {
            TeamEntity team = teams(orgId).stream()
                .filter(t -> t.getId().equals(teamId)).findFirst().orElseThrow();
            jdbc.update("insert into team_memberships (team_id, user_id, role, joined_at) "
                + "values (?, ?, ?, now())", team.getId(), id, role);
        } else {
            jdbc.update("insert into organization_memberships "
                + "(organization_id, user_id, role, joined_at) values (?, ?, ?, now())",
                orgId, id, role);
        }
        return new CurrentUser(id, "rev-" + id, "Reviewer", null);
    }

    /**
     * 一个人同时有组织身份与团队身份——接受邀请之后就是这样：
     * 组织角色被团队角色那行盖住之前，两者是分开的。
     */
    private CurrentUser userWithRoles(UUID orgId, UUID teamId, String orgRole, String teamRole) {
        CurrentUser user = userWithRole(orgId, null, orgRole);
        jdbc.update("insert into team_memberships (team_id, user_id, role, joined_at) "
            + "values (?, ?, ?, now())", teamId, user.id(), teamRole);
        return user;
    }

    private List<TeamEntity> teams(UUID orgId) {
        return orgs.teamsOf(orgId);
    }

    private UUID createTeamSkill(UUID orgId, UUID teamId, String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "slug", slug, "name", slug, "description", "",
                    "visibility", "team",
                    "organization_id", orgId.toString(),
                    "owner_team_id", teamId.toString()))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("id").asText());
    }

    private UUID createSkill(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "slug", slug, "name", slug, "description", "", "visibility", "private"))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("id").asText());
    }

    private void publish(UUID skillId, String version) throws Exception {
        publish(skillId, version, "# " + version + "\n");
    }

    /** 传指定内容的一版。内容里带凭据就会被服务端扫描拦下。 */
    private void publish(UUID skillId, String version, String content) throws Exception {
        byte[] zip = TestArtifacts.zipOf("SKILL.md", content);
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
        HttpResponse<String> put = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(node.path("upload").path("url").asText()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(zip)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isEqualTo(200);
        mockMvc.perform(post("/api/v1/uploads/{id}/complete",
                node.path("upload").path("session_id").asText())
                .header("Authorization", auth))
            .andExpect(status().isAccepted());
    }

    /** 只建版本不上传——版本停在 draft，没有制品。 */
    private void createDraft(UUID skillId, String version) throws Exception {
        byte[] zip = TestArtifacts.zipOf("SKILL.md", "# " + version + "\n");
        mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "version", version, "channel", "stable", "size_bytes", zip.length,
                    "sha256", TestArtifacts.sha256(zip), "file_count", 1,
                    "manifest", List.of(Map.of("path", "SKILL.md",
                        "sha256", TestArtifacts.sha256(zip), "size_bytes", zip.length))))))
            .andExpect(status().isCreated());
    }
}
