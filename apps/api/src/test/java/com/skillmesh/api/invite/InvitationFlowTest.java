package com.skillmesh.api.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.invite.dto.InvitationPage;
import com.skillmesh.api.invite.dto.InvitationSummary;
import com.skillmesh.api.org.OrgService;
import com.skillmesh.api.support.DatabaseCleaner;
import com.skillmesh.api.support.QueryCounter;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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
    @Autowired private OrgService orgs;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private String auth;
    private CurrentUser owner;

    @BeforeEach
    void 准备() throws Exception {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
        owner = currentUser();
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

    /** 不带请求体 = 组织级邀请：controller 的默认值不能撞上「给了团队角色就得给团队」。 */
    @Test
    void 不带请求体也能建组织级邀请() throws Exception {
        UUID orgId = createOrganization("acme");

        mockMvc.perform(post("/api/v1/organizations/{id}/invitations", orgId)
                .header("Authorization", auth))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token").exists())
            .andExpect(jsonPath("$.organization_slug").value("acme"))
            .andExpect(jsonPath("$.org_role").value("member"));

        // 落库的是组织级邀请：没有团队
        assertThat(jdbc.queryForObject(
            "select count(*) from org_invitations where organization_id = ? and team_id is null",
            Integer.class, orgId)).isEqualTo(1);
        // team_role 是 NOT NULL，存的是兜底值
        assertThat(jdbc.queryForObject(
            "select team_role from org_invitations where organization_id = ?",
            String.class, orgId)).isEqualTo("member");
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
        // 组织由别人建：真令牌的用户在里面还没有身份，接受之后才有。
        CurrentUser admin = stranger();
        UUID orgId = orgs.createOrganization(admin, "acme", "acme").getId();
        UUID teamId = orgs.createTeam(admin, orgId, "platform", "platform", null).getId();
        String token = invitations.create(admin, orgId, teamId, "member", "member", null).token();

        mockMvc.perform(post("/api/v1/invitations/{token}/accept", token)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.org_role").value("member"))
            .andExpect(jsonPath("$.team_id").value(teamId.toString()));

        // 成员关系真的落库了——响应体是拿 invitation 拼的，不能当落库的证据
        assertThat(orgs.roleIn(orgId, null, owner.id())).isEqualTo("member");
        assertThat(orgs.roleIn(orgId, teamId, owner.id())).isEqualTo("member");

        // 重复接受幂等，角色不会被重授一遍
        mockMvc.perform(post("/api/v1/invitations/{token}/accept", token)
                .header("Authorization", auth))
            .andExpect(status().isOk());
        assertThat(orgs.roleIn(orgId, null, owner.id())).isEqualTo("member");
        assertThat(orgs.roleIn(orgId, teamId, owner.id())).isEqualTo("member");
    }

    /** 邀请被一个人接受后，另一个人拿着同一条链接再来不能拿到「已加入」。 */
    @Test
    void 邀请被接受后别人再来是409() throws Exception {
        CurrentUser admin = stranger();
        UUID orgId = orgs.createOrganization(admin, "acme", "acme").getId();
        String token = invitations.create(admin, orgId, null, "member", null, null).token();

        invitations.accept(owner, token);

        ApiException ex = catchThrowableOfType(
            () -> invitations.accept(stranger(), token), ApiException.class);
        assertThat(ex).as("别人不能复用别人的邀请").isNotNull();
        assertThat(ex.status()).isEqualTo(409);
        assertThat(ex.code()).isEqualTo("INVITATION_INVALID");
    }

    /**
     * 接受邀请只升不降：唯一 owner 接受一份 member 邀请，
     * 组织从此没有 owner，而 owner 只能由现任 owner 任命——那是永久锁死。
     */
    @Test
    void 接受邀请不会降级已有角色() throws Exception {
        UUID orgId = createOrganization("acme");
        String token = createInvitation(orgId, null);

        mockMvc.perform(post("/api/v1/invitations/{token}/accept", token)
                .header("Authorization", auth))
            .andExpect(status().isOk());

        assertThat(orgs.roleIn(orgId, null, owner.id())).isEqualTo("owner");
    }

    /**
     * 绑定了 subject 的邀请只有本人能接受。
     *
     * 不绑的话，链接谁拿到谁能接受——而链接是要转发出去的（页面直接把链接与
     * 原始 token 显示出来供复制）。这条闸以前从没被触发过：控制台发的邀请
     * 从来不带 subject。
     */
    @Test
    void 绑定成员的邀请别人接受不了() throws Exception {
        CurrentUser admin = stranger();
        UUID orgId = orgs.createOrganization(admin, "acme", "acme").getId();
        String token = invitations.create(admin, orgId, null, "member", null, owner.subject()).token();

        ApiException ex = catchThrowableOfType(
            () -> invitations.accept(stranger(), token), ApiException.class);
        assertThat(ex).as("绑了人的邀请别人接不了").isNotNull();
        assertThat(ex.status()).isEqualTo(403);

        // 本人接受照常
        invitations.accept(owner, token);
        assertThat(orgs.roleIn(orgId, null, owner.id())).isEqualTo("member");
    }

    /** 过期邀请：摘要显示 expired，接受回 409。 */
    @Test
    void 过期邀请不能接受() throws Exception {
        UUID orgId = createOrganization("acme");
        String token = createInvitation(orgId, null);
        jdbc.update("update org_invitations set expires_at = now() - interval '1 day' where id = ?",
            invitationId(token));

        mockMvc.perform(get("/api/v1/invitations/{token}", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("expired"));

        mockMvc.perform(post("/api/v1/invitations/{token}/accept", token)
                .header("Authorization", auth))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
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
        ApiException ex = catchThrowableOfType(
            () -> invitations.create(stranger(), orgId, teamId, "member", "member", null),
            ApiException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.status()).isEqualTo(403);
        assertThat(ex.code()).isEqualTo("FORBIDDEN");
    }

    /** token_hash 是 UNIQUE——哈希撞了得让数据库拦下来，不能存两条一样的凭据。 */
    @Test
    void 重复的token哈希被数据库拒绝() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");
        createInvitation(orgId, teamId);
        String hash = jdbc.queryForObject(
            "select token_hash from org_invitations limit 1", String.class);

        // 断言到具体约束名——NOT NULL / 外键违规也抛 DataIntegrityViolationException
        assertThatThrownBy(() -> jdbc.update(
            "insert into org_invitations (id, organization_id, org_role, team_role, "
                + "token_hash, status, created_at, expires_at) "
                + "values (?, ?, 'member', 'member', ?, 'pending', now(), now() + interval '7 days')",
            UUID.randomUUID(), orgId, hash))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("org_invitations_token_hash_key");
    }

    /** owner 不能通过邀请拿到——邀请只能给到 admin 及以下。 */
    @Test
    void 邀请不能授予owner() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");
        CurrentUser admin = adminOf(orgId);

        // HTTP 层：请求体校验就拒
        mockMvc.perform(post("/api/v1/organizations/{id}/invitations", orgId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "team_id", teamId.toString(), "org_role", "owner", "team_role", "member"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 服务层：不经过请求体校验也要拒
        ApiException ex = catchThrowableOfType(
            () -> invitations.create(admin, orgId, teamId, "owner", "member", null),
            ApiException.class);
        assertThat(ex).as("服务层也要拒 owner 邀请").isNotNull();
        assertThat(ex.status()).isEqualTo(400);
        assertThat(ex.code()).isEqualTo("VALIDATION_FAILED");
    }

    /** 给了团队角色却没给团队：那个角色永远用不上，不能装作给了。 */
    @Test
    void 给了团队角色却没给团队是400() throws Exception {
        UUID orgId = createOrganization("acme");

        mockMvc.perform(post("/api/v1/organizations/{id}/invitations", orgId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "org_role", "member", "team_role", "admin"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** admin 任命 owner 等于提权：owner 只能由现任 owner 亲手给。 */
    @Test
    void admin不能授予owner() throws Exception {
        UUID orgId = createOrganization("acme");
        CurrentUser admin = adminOf(orgId);
        UUID target = stranger().id();

        ApiException ex = catchThrowableOfType(
            () -> orgs.upsertOrgMember(admin, orgId, target, "owner"), ApiException.class);
        assertThat(ex).as("admin 不能通过 upsertOrgMember 任命 owner").isNotNull();
        assertThat(ex.status()).isEqualTo(403);
        assertThat(ex.code()).isEqualTo("FORBIDDEN");

        // 既有的 addOrgMember 是同一个洞，一起堵上
        ApiException direct = catchThrowableOfType(
            () -> orgs.addOrgMember(admin, orgId, target, "owner"), ApiException.class);
        assertThat(direct).as("admin 不能通过 addOrgMember 任命 owner").isNotNull();
        assertThat(direct.status()).isEqualTo(403);
        assertThat(direct.code()).isEqualTo("FORBIDDEN");
    }

    /** 正常路径没被堵死：owner 亲手授予 owner 是允许的。 */
    @Test
    void owner能授予owner() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID target = stranger().id();

        orgs.upsertOrgMember(owner, orgId, target, "owner");
        assertThat(orgs.roleIn(orgId, null, target)).isEqualTo("owner");
    }

    /**
     * 两个人同时接受同一份邀请：只能有一个成功，另一个拿 409。
     *
     * 不加锁的话两个事务会同时读到 pending、同时写成员关系——
     * 一份单次令牌把两个人拉进组织。
     */
    @Test
    void 两人并发接受同一份邀请只有一人成功() throws Exception {
        CurrentUser admin = stranger();
        UUID orgId = orgs.createOrganization(admin, "acme", "acme").getId();
        String token = invitations.create(admin, orgId, null, "member", null, null).token();
        CurrentUser alice = stranger();
        CurrentUser bob = stranger();

        List<Object> results = acceptConcurrently(token, alice, bob);

        assertThat(results).filteredOn(r -> r instanceof InvitationService.Accepted)
            .as("恰好一个人接受成功").hasSize(1);
        ApiException conflict = results.stream()
            .filter(r -> r instanceof ApiException)
            .map(r -> (ApiException) r)
            .findFirst().orElseThrow();
        assertThat(conflict.status()).as("输的那个拿 409，不是 500").isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("INVITATION_INVALID");
        // 组织里只多了一个成员（管理员 + 那个接受成功的人）
        assertThat(orgs.orgMembers(orgId)).hasSize(2);
    }

    /** 同一个人开两个标签页同时提交：两次都幂等成功，不能出 500。 */
    @Test
    void 同一用户并发接受两次都幂等成功() throws Exception {
        CurrentUser admin = stranger();
        UUID orgId = orgs.createOrganization(admin, "acme", "acme").getId();
        String token = invitations.create(admin, orgId, null, "member", null, null).token();
        CurrentUser alice = stranger();

        List<Object> results = acceptConcurrently(token, alice, alice);

        assertThat(results).filteredOn(r -> r instanceof InvitationService.Accepted)
            .as("两次都是幂等成功").hasSize(2);
        assertThat(orgs.orgMembers(orgId)).hasSize(2);
    }

    /**
     * 响应里的角色是落库后的实际角色：本来就是 publisher 的人接受一份 member 邀请，
     * 不能被说成 member（设计文档 §5.1）。
     */
    @Test
    void 接受邀请返回落库后的实际角色() throws Exception {
        CurrentUser admin = stranger();
        UUID orgId = orgs.createOrganization(admin, "acme", "acme").getId();
        CurrentUser bob = stranger();
        orgs.upsertOrgMember(admin, orgId, bob.id(), "publisher");
        String token = invitations.create(admin, orgId, null, "member", null, null).token();

        InvitationService.Accepted accepted = invitations.accept(bob, token);

        assertThat(accepted.orgRole()).isEqualTo("publisher");
        assertThat(orgs.roleIn(orgId, null, bob.id())).isEqualTo("publisher");
    }

    /** 团队角色在服务层也要校验：脏值不能撞 DDL 的 CHECK 变成 500。 */
    @Test
    void 服务层也校验团队角色() {
        CurrentUser admin = stranger();
        UUID orgId = orgs.createOrganization(admin, "acme", "acme").getId();
        UUID teamId = orgs.createTeam(admin, orgId, "platform", "platform", null).getId();

        ApiException ex = catchThrowableOfType(
            () -> invitations.create(admin, orgId, teamId, "member", "owner", null),
            ApiException.class);
        assertThat(ex).as("团队角色非法要 400，不是 500").isNotNull();
        assertThat(ex.status()).isEqualTo(400);
        assertThat(ex.code()).isEqualTo("VALIDATION_FAILED");
    }

    /**
     * 邀请列表一次查齐组织与团队。
     *
     * 原来每条邀请都要再查一次组织、一次团队——一个组织攒下几十份邀请
     * （邀请只撤销不删，越攒越多）就是几十次往返，本机到远程库一次几十毫秒，
     * 这个页面直接卡住。
     */
    @Test
    void 邀请列表一次查齐组织与团队() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");
        createInvitation(orgId, teamId);
        createInvitation(orgId, teamId);
        createInvitation(orgId, teamId);
        createInvitation(orgId, null);

        QueryCounter counter = new QueryCounter(entityManagerFactory);
        long queries = counter.count(() -> {
            InvitationPage page = invitations.listSummaries(orgId, 20, null);
            assertThat(page.items()).as("四条邀请都在列表里").hasSize(4);
        });

        assertThat(queries).as("列表页的查询条数不该随邀请条数增长")
            .isLessThanOrEqualTo(5);
    }

    /**
     * 邀请列表翻页不重不漏。
     *
     * 五条邀请的 created_at 设成同一时刻：created_at 单列会重复，
     * 翻页只能靠 id 兜底排序——顺序差一点就会漏掉或重复一条。
     */
    @Test
    void 邀请列表翻页不重不漏() throws Exception {
        UUID orgId = createOrganization("acme");
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(invitations.create(owner, orgId, null, "member", null, null).id());
        }
        jdbc.update("update org_invitations set created_at = ? where organization_id = ?",
            OffsetDateTime.now(ZoneOffset.UTC), orgId);

        var page = json.readTree(mockMvc.perform(
                get("/api/v1/organizations/{id}/invitations", orgId)
                    .header("Authorization", auth).param("limit", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.has_more").value(true))
            .andExpect(jsonPath("$.next_cursor").isNotEmpty())
            .andReturn().getResponse().getContentAsString());

        List<String> seen = new ArrayList<>();
        page.path("items").forEach(n -> seen.add(n.path("id").asText()));
        String cursor = page.path("next_cursor").asText(null);
        while (cursor != null && !cursor.isBlank()) {
            var next = json.readTree(mockMvc.perform(
                    get("/api/v1/organizations/{id}/invitations", orgId)
                        .header("Authorization", auth)
                        .param("limit", "2").param("cursor", cursor))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
            next.path("items").forEach(n -> seen.add(n.path("id").asText()));
            cursor = next.path("has_more").asBoolean()
                ? next.path("next_cursor").asText(null) : null;
        }

        // 五条都在：一条不多，一条不少
        assertThat(seen).hasSize(5);
        assertThat(new HashSet<>(seen)).as("翻完一遍拿到全部五条，且不重复")
            .containsExactlyInAnyOrderElementsOf(created.stream().map(UUID::toString).toList());
    }

    /**
     * 两个线程同时接受同一份邀请，返回各自的结果（成功对象或异常）。
     *
     * 先用 SELECT ... FOR UPDATE 把邀请行锁住：两个线程的「读状态」都发生在
     * 任何一方提交之前——不锁这一行的话，谁先提交另一个就可能直接读到 accepted，
     * 用例会变成「运气好也通过」。
     */
    private List<Object> acceptConcurrently(String token, CurrentUser first, CurrentUser second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            pool.submit(() -> {
                try (Connection conn = dataSource.getConnection()) {
                    conn.setAutoCommit(false);
                    try (PreparedStatement ps = conn.prepareStatement(
                            "select id from org_invitations for update")) {
                        ps.executeQuery().close();
                    }
                    locked.countDown();
                    release.await(30, TimeUnit.SECONDS);
                    conn.commit();
                }
                return null;
            });
            assertThat(locked.await(30, TimeUnit.SECONDS)).as("锁已拿到").isTrue();

            CountDownLatch go = new CountDownLatch(1);
            Future<Object> a = pool.submit(() -> acceptQuietly(go, first, token));
            Future<Object> b = pool.submit(() -> acceptQuietly(go, second, token));
            go.countDown();
            // 给两个线程时间把「读状态」做完（随后它们会卡在锁上）
            Thread.sleep(1500);
            release.countDown();

            return List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    /** 接受一次，成功返回结果对象，失败返回异常对象。 */
    private Object acceptQuietly(CountDownLatch go, CurrentUser user, String token) {
        try {
            go.await();
            return invitations.accept(user, token);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return e;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private String createInvitation(UUID orgId, UUID teamId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("org_role", "member");
        // 给了团队角色就必须给团队——所以团队角色跟着团队一起给
        if (teamId != null) {
            body.put("team_id", teamId.toString());
            body.put("team_role", "member");
        }
        String response = mockMvc.perform(post("/api/v1/organizations/{id}/invitations", orgId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("token").asText();
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

    /** 端到端里唯一的真令牌对应的用户——组织就是以它的身份建的，所以它是 owner。 */
    private CurrentUser currentUser() throws Exception {
        String body = mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
            .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).path("user").path("id").asText());
        String subject = jdbc.queryForObject(
            "select oidc_subject from users where id = ?", String.class, id);
        return new CurrentUser(id, subject, "Owner", null);
    }

    /** 造一个组织管理员——admin 能管成员，但不该能任命 owner。 */
    private CurrentUser adminOf(UUID orgId) {
        CurrentUser admin = stranger();
        orgs.addOrgMember(owner, orgId, admin.id(), "admin");
        return admin;
    }

    /** 造一个真实用户行——成员表对 users 有外键，光给个 UUID 插不进去。 */
    private CurrentUser stranger() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, oidc_issuer, oidc_subject, display_name, status) "
            + "values (?, 'https://kc', ?, 'Stranger', 'active')", id, "stranger-" + id);
        return new CurrentUser(id, "stranger-" + id, "Stranger", null);
    }
}
