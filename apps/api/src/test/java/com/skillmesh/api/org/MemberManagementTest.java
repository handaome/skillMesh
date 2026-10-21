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
import java.time.OffsetDateTime;
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
    void admin不能降级owner() {
        UUID orgId = createOrganizationQuietly("acme");
        CurrentUser admin = stranger();
        orgs.addOrgMember(owner, orgId, admin.id(), "admin");

        // 改 owner 身份只能由 owner 动手：admin 把 owner 降级，
        // 等于做了只有 owner 该做的事。
        assertThatThrownBy(() -> orgs.updateOrgRole(admin, orgId, owner.id(), "admin"))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.status()).isEqualTo(403);
                assertThat(ex.code()).isEqualTo(ErrorCodes.FORBIDDEN);
            });
        assertThat(orgs.orgMembers(orgId))
            .anyMatch(m -> owner.id().equals(m.userId()) && "owner".equals(m.role()));
    }

    @Test
    void admin加成员时也不能改owner身份() {
        UUID orgId = createOrganizationQuietly("acme");
        CurrentUser admin = stranger();
        orgs.addOrgMember(owner, orgId, admin.id(), "admin");

        // 加成员也会改角色，同一道闸得同样堵住
        assertThatThrownBy(() -> orgs.addOrgMember(admin, orgId, owner.id(), "member"))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.status()).isEqualTo(403);
                assertThat(ex.code()).isEqualTo(ErrorCodes.FORBIDDEN);
            });
        assertThat(orgs.orgMembers(orgId))
            .anyMatch(m -> owner.id().equals(m.userId()) && "owner".equals(m.role()));
    }

    @Test
    void 最后一个owner不能被降级() {
        UUID orgId = createOrganizationQuietly("acme");

        // 组织里就一个 owner，他把自己降级——降了之后
        // requireOwnerToGrantOwner 再也满足不了，组织永久失去 owner。
        assertThatThrownBy(() -> orgs.addOrgMember(owner, orgId, owner.id(), "member"))
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

    @Test
    void 跨组织的花名册看不见() throws Exception {
        UUID orgA = createOrganization("acme");           // 调用者是 A 的 owner
        UUID teamA = createTeam(orgA, "platform");
        CurrentUser other = stranger();
        UUID orgB = orgs.createOrganization(other, "beta", "Beta").getId();
        UUID teamB = orgs.createTeam(other, orgB, "payments", "Payments", null).getId();
        orgs.addTeamMember(other, teamB, other.id(), "member");

        // B 的组织花名册：调用者不是 B 的成员
        mockMvc.perform(get("/api/v1/organizations/{id}/members", orgB)
                .header("Authorization", auth))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/organizations/{id}/teams/{tid}/members", orgB, teamB)
                .header("Authorization", auth))
            .andExpect(status().isNotFound());

        // 拿 A 的组织 id 要 B 的团队花名册：团队不在 A 里，看不见
        mockMvc.perform(get("/api/v1/organizations/{id}/teams/{tid}/members", orgA, teamB)
                .header("Authorization", auth))
            .andExpect(status().isNotFound());

        // 写路径也得过团队归属这一道：同一个资源路径，读写答案要一致
        mockMvc.perform(patch("/api/v1/organizations/{id}/teams/{tid}/members/{uid}",
                orgA, teamB, other.id())
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"viewer\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/organizations/{id}/teams/{tid}/members/{uid}",
                orgA, teamB, other.id())
                .header("Authorization", auth))
            .andExpect(status().isNotFound());
        assertThat(orgs.teamMembers(teamB)).as("写路径被挡下，成员没被动").isNotEmpty();

        // 对照：自己组织的团队花名册看得见
        mockMvc.perform(get("/api/v1/organizations/{id}/teams/{tid}/members", orgA, teamA)
                .header("Authorization", auth))
            .andExpect(status().isOk());
    }

    @Test
    void 非publisher不能建团队可见的Skill() throws Exception {
        CurrentUser other = stranger();
        UUID orgId = orgs.createOrganization(other, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(other, orgId, "platform", "Platform", null).getId();
        orgs.addOrgMember(other, orgId, owner.id(), "member");   // 调用者只是 member

        mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "slug", "sneaky-team", "name", "Sneaky", "description", "",
                    "visibility", "team",
                    "organization_id", orgId.toString(),
                    "owner_team_id", teamId.toString()))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 组织角色是publisher时不受团队角色影响() throws Exception {
        CurrentUser other = stranger();
        UUID orgId = orgs.createOrganization(other, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(other, orgId, "platform", "Platform", null).getId();
        orgs.addOrgMember(other, orgId, owner.id(), "publisher");   // 组织角色够
        orgs.addTeamMember(other, teamId, owner.id(), "member");    // 团队角色不够

        // 判的是组织角色（与 CLI、手册一致）→ 放行。
        // 接受邀请会写一行 team_role=member 把组织角色遮住，
        // 服务端若查团队角色，手册给的解法（提组织角色）就失效了。
        mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "slug", "org-publisher", "name", "Org Publisher", "description", "",
                    "visibility", "team",
                    "organization_id", orgId.toString(),
                    "owner_team_id", teamId.toString()))))
            .andExpect(status().isCreated());
    }

    @Test
    void 非publisher不能建组织可见的Skill() throws Exception {
        CurrentUser other = stranger();
        UUID orgId = orgs.createOrganization(other, "acme", "Acme").getId();
        orgs.addOrgMember(other, orgId, owner.id(), "member");   // 调用者只是 member

        mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "slug", "sneaky-org", "name", "Sneaky", "description", "",
                    "visibility", "organization",
                    "organization_id", orgId.toString()))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /**
     * 团队可见的 Skill，团队必须属于同一个组织。
     *
     * 团队与组织是两套身份：owner_team_id 指向别的组织的团队时，
     * 「组织角色」与「团队角色」对不上，发布权那套判断也就无从谈起。
     */
    @Test
    void 团队不属于该组织时建不了团队可见的Skill() throws Exception {
        UUID orgA = createOrganization("acme-a");
        UUID teamA = createTeam(orgA, "platform");
        UUID orgB = createOrganization("acme-b");

        mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "slug", "cross-team", "name", "Cross", "description", "",
                    "visibility", "team",
                    "organization_id", orgB.toString(),
                    "owner_team_id", teamA.toString()))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /**
     * private/public 可见的 Skill 不属于任何组织或团队。
     *
     * 带着 organization_id 建出来的私有 Skill 会落进该组织的审核队列
     * （队列按 organization_id is not null 过滤），私有内容就混进审核页了。
     */
    @Test
    void private可见的Skill不许带organization_id() throws Exception {
        UUID orgId = createOrganization("acme");

        mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "slug", "private-org", "name", "Private Org", "description", "",
                    "visibility", "private",
                    "organization_id", orgId.toString()))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** 同样不许带 owner_team_id。 */
    @Test
    void public可见的Skill不许带owner_team_id() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");

        mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "slug", "public-team", "name", "Public Team", "description", "",
                    "visibility", "public",
                    "owner_team_id", teamId.toString()))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /**
     * 「加成员」不该顺手削掉别人的权限，也不该重置加入时间：
     * 已经是 reviewer 的人被再加一次，角色还得是 reviewer。
     */
    @Test
    void 加成员不静默降级已有角色() {
        UUID orgId = createOrganizationQuietly("acme");
        UUID teamId = createTeamQuietly(orgId, "platform");
        CurrentUser member = stranger();
        orgs.upsertTeamMember(owner, teamId, member.id(), "reviewer");
        OffsetDateTime joinedAt = orgs.teamMembers(teamId).get(0).joinedAt();

        orgs.addTeamMember(owner, teamId, member.id(), "member");

        assertThat(orgs.teamMembers(teamId))
            .anyMatch(m -> member.id().equals(m.userId()) && "reviewer".equals(m.role()));
        assertThat(orgs.teamMembers(teamId).get(0).joinedAt()).as("加入时间没被重置")
            .isEqualTo(joinedAt);
    }

    @Test
    void 并发降级不会把owner降光() throws Exception {
        UUID orgId = createOrganizationQuietly("acme");
        CurrentUser second = stranger();
        orgs.addOrgMember(owner, orgId, second.id(), "owner");

        // 两个 owner 互相降级：不串行化的话两边都看到 ownerCount == 2，
        // 各自降一个，组织就剩 0 个 owner——永久锁死。
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> a = pool.submit(() -> demote(start, owner, orgId, second.id()));
            Future<Boolean> b = pool.submit(() -> demote(start, second, orgId, owner.id()));
            start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                .as("恰好一个降级成功，另一个被守卫挡下")
                .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        assertThat(orgs.orgMembers(orgId))
            .filteredOn(m -> "owner".equals(m.role()))
            .as("组织里还剩 owner").hasSize(1);
    }

    /** 降级成功返回 true，被守卫挡下返回 false。 */
    private boolean demote(CountDownLatch start, CurrentUser actor, UUID orgId, UUID target) {
        try {
            start.await();
            orgs.updateOrgRole(actor, orgId, target, "admin");
            return true;
        } catch (ApiException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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
