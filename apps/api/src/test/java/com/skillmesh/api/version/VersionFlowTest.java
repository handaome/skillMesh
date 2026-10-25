package com.skillmesh.api.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.org.OrgService;
import com.skillmesh.api.support.QueryCounter;
import com.skillmesh.api.version.dto.CreateVersionRequest;
import jakarta.persistence.EntityManagerFactory;
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
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.support.DatabaseCleaner;
import com.skillmesh.api.support.TestArtifacts;

/** 版本发布全流程：建版本 → 直传对象存储 → 服务端校验 → 发布 → 下载。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VersionFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;
    @Autowired private OrgService orgs;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SkillVersionService versions;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private String auth;

    @BeforeEach
    void 准备() throws Exception {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
    }

    @Test
    void 完整发布流程并下载校验摘要() throws Exception {
        UUID skillId = createSkill("demo-skill");
        byte[] zip = zipOf("SKILL.md", "# Demo Skill\n");

        String created = mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(versionBody("1.0.0", zip)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var node = json.readTree(created);
        String uploadUrl = node.path("upload").path("url").asText();
        String sessionId = node.path("upload").path("session_id").asText();

        putToStorage(uploadUrl, zip);

        mockMvc.perform(post("/api/v1/uploads/{id}/complete", sessionId)
                .header("Authorization", auth))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("completed"))
            .andExpect(jsonPath("$.verified_sha256").value(sha256(zip)));

        String versionsJson = mockMvc.perform(get("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].version").value("1.0.0"))
            .andExpect(jsonPath("$.items[0].status").value("published"))
            // manifest 要透出来：客户端靠它判断内容是否真的变了。
            // 而且透出来的得是**服务端从真包算的**那份——发布方自填的只是他的说法。
            .andExpect(jsonPath("$.items[0].manifest[0].path").value("SKILL.md"))
            .andExpect(jsonPath("$.items[0].manifest[0].sha256")
                .value(sha256("# Demo Skill\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            .andReturn().getResponse().getContentAsString();
        var version = json.readTree(versionsJson).path("items").get(0);
        String artifactId = version.path("artifact").path("id").asText();
        assertThat(version.path("artifact").path("sha256").asText()).isEqualTo(sha256(zip));

        byte[] downloaded = mockMvc.perform(get("/api/v1/artifacts/{id}/download", artifactId)
                .header("Authorization", auth).param("disposition", "stream"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(zip);
    }

    @Test
    void 摘要不匹配则拒绝发布() throws Exception {
        UUID skillId = createSkill("mismatch");
        byte[] real = zipOf("SKILL.md", "# Real\n");

        String created = mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(versionBodyWithDigest("1.0.0", real, "b".repeat(64))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var node = json.readTree(created);
        putToStorage(node.path("upload").path("url").asText(), real);

        mockMvc.perform(post("/api/v1/uploads/{id}/complete",
                node.path("upload").path("session_id").asText())
                .header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("DIGEST_MISMATCH"));

        // 版本不应被发布
        mockMvc.perform(get("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth))
            .andExpect(jsonPath("$.items[0].status").value("draft"));
    }

    @Test
    void 同版本号二次创建返回409() throws Exception {
        UUID skillId = createSkill("dup-version");
        byte[] zip = zipOf("SKILL.md", "# X\n");
        mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(versionBody("1.0.0", zip)))
            .andExpect(status().isCreated());

        var result = mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(versionBody("1.0.0", zip)))
            .andExpect(status().isConflict())
            .andReturn();
        assertThat(json.readTree(result.getResponse().getContentAsString()).path("code").asText())
            .isEqualTo("VERSION_CONFLICT");
    }

    @Test
    void 同一份内容发成两个版本时仍可下载() throws Exception {
        UUID skillId = createSkill("same-content");
        byte[] zip = zipOf("SKILL.md", "# Same\n");

        String first = publishVersion(skillId, "1.0.0", zip);
        String second = publishVersion(skillId, "1.0.1", zip);
        // 两次内容相同 → sha256 相同 → 共用同一条 artifacts 记录
        assertThat(json.readTree(first).path("artifact").path("id").asText())
            .isEqualTo(json.readTree(second).path("artifact").path("id").asText());

        String artifactId = json.readTree(first).path("artifact").path("id").asText();
        byte[] downloaded = mockMvc.perform(get("/api/v1/artifacts/{id}/download", artifactId)
                .header("Authorization", auth).param("disposition", "stream"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(zip);
    }

    /**
     * 服务端独立扫描：含真实凭据的包不能发布（详细设计 §10、§15.1）。
     * 这条不依赖客户端——绕开 CLI 直接 POST 也得被拦。
     *
     * 落的是 quarantined 而不是 rejected：「内容里有真凭据」与
     * 「审核者觉得不合适」是两回事，流水要能分开看（设计文档 §10.1）。
     */
    @Test
    void 含凭据的包被服务端扫描拦下() throws Exception {
        UUID skillId = createSkill("leaky");
        byte[] zip = zipOf("SKILL.md", "# Leaky\n\nexport AWS_KEY=AKIAIOSFODNN7EXAMPLE\n");

        String created = mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(versionBody("1.0.0", zip)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var node = json.readTree(created);
        putToStorage(node.path("upload").path("url").asText(), zip);
        mockMvc.perform(post("/api/v1/uploads/{id}/complete",
                node.path("upload").path("session_id").asText())
                .header("Authorization", auth))
            .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].status").value("quarantined"))
            .andExpect(jsonPath("$.items[0].scan.status").value("failed"))
            .andExpect(jsonPath("$.items[0].scan.scanner_name").value("skillmesh-server-scan"))
            .andExpect(jsonPath("$.items[0].scan.findings[0].rule").value("AWS Access Key"));
    }

    /** 干净包扫描通过，版本照常发布。 */
    @Test
    void 干净包扫描通过照常发布() throws Exception {
        UUID skillId = createSkill("clean");
        byte[] zip = zipOf("SKILL.md", "# Clean\n\n用 your-key-here 这种占位符没问题。\n");
        publishVersion(skillId, "1.0.0", zip);
        mockMvc.perform(get("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth))
            .andExpect(jsonPath("$.items[0].status").value("published"))
            .andExpect(jsonPath("$.items[0].scan.status").value("passed"));
    }

    /**
     * 团队 Skill 发新版本要发布权：只判可见性的话，团队的普通成员
     * 也能往待审队列里塞一版内容。
     */
    @Test
    void 团队普通成员建版本被拒() throws Exception {
        UUID orgId = createOrg("acme");
        UUID teamId = createTeam(orgId, "platform");
        UUID skillId = createTeamSkill(orgId, teamId, "runbook");
        CurrentUser member = userWithRoles(orgId, teamId, "member", "member");

        ApiException err = catchThrowableOfType(
            () -> versions.create(member, skillId, versionRequest("1.0.0")),
            ApiException.class);
        assertThat(err).as("团队普通成员没有发布权").isNotNull();
        assertThat(err.status()).isEqualTo(403);
        assertThat(err.code()).isEqualTo("FORBIDDEN");
        assertThat(err).hasMessageContaining("publisher");
    }

    /**
     * 组织角色 publisher 就能发——判的是组织角色，团队角色不够也不挡。
     * 这正是「接受邀请之后」的状态：组织角色 publisher，团队角色 member。
     */
    @Test
    void 组织角色publisher能建团队版本() throws Exception {
        UUID orgId = createOrg("acme");
        UUID teamId = createTeam(orgId, "platform");
        UUID skillId = createTeamSkill(orgId, teamId, "runbook");
        CurrentUser publisher = userWithRoles(orgId, teamId, "publisher", "member");

        assertThat(versions.create(publisher, skillId, versionRequest("1.0.0")).versionId())
            .as("组织角色够就能建版本").isNotNull();
    }

    /**
     * 公开 Skill 的发版权也锁属主。
     *
     * public 谁都看得见，若发版也放开发版，任何登录用户都能往别人的公开 Skill
     * 上塞一版并顶掉 latest_version——而改名字（PATCH）与撤回都锁属主，只有发版不锁。
     */
    @Test
    void 公开Skill别人发版本被拒() throws Exception {
        UUID skillId = createSkillWithVisibility("public-demo", "public");
        CurrentUser stranger =
            new CurrentUser(UUID.randomUUID(), "stranger", "Stranger", "s@example.com");

        ApiException err = catchThrowableOfType(
            () -> versions.create(stranger, skillId, versionRequest("1.0.0")),
            ApiException.class);
        assertThat(err).as("公开 Skill 只有属主能发版").isNotNull();
        assertThat(err.status()).isEqualTo(403);
        assertThat(err.code()).isEqualTo("FORBIDDEN");
    }

    /** 公开 Skill 的属主发新版本照旧。 */
    @Test
    void 公开Skill属主发版本不受影响() throws Exception {
        UUID skillId = createSkillWithVisibility("public-owner", "public");
        assertThat(versions.create(owner(), skillId, versionRequest("1.0.0")).versionId())
            .as("公开 Skill 属主发新版本").isNotNull();
    }
    /** 个人 Skill 属主自己发新版本不受影响——v1 的既有行为别误伤。 */
    @Test
    void 个人Skill属主建新版本不受影响() throws Exception {
        UUID skillId = createSkill("personal");
        assertThat(versions.create(owner(), skillId, versionRequest("1.0.0")).versionId())
            .as("个人 Skill 属主发新版本").isNotNull();
    }

    /**
     * 版本详情只查这一个版本。
     *
     * 原来 detail() 借 list() 做可见性校验，顺手把该 Skill 的全部版本
     * 连同每个版本的制品与扫描都查了一遍——版本一多，看一个版本要等几十次往返。
     */
    @Test
    void 版本详情不查该技能的全部版本() throws Exception {
        UUID skillId = createSkill("detail-queries");
        insertPublishedVersions(skillId, 5);

        QueryCounter counter = new QueryCounter(entityManagerFactory);
        long queries = counter.count(() -> mockMvc
            .perform(get("/api/v1/skills/{id}/versions/{v}", skillId, "1.0.3")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value("1.0.3")));

        assertThat(queries).as("看一个版本不该把该 Skill 的所有版本都查一遍")
            .isLessThanOrEqualTo(7);
    }

    /** 版本列表里的制品与扫描一次批量查齐，不逐个版本查。 */
    @Test
    void 版本列表批量取制品与扫描() throws Exception {
        UUID skillId = createSkill("list-queries");
        insertPublishedVersions(skillId, 5);

        QueryCounter counter = new QueryCounter(entityManagerFactory);
        long queries = counter.count(() -> mockMvc
            .perform(get("/api/v1/skills/{id}/versions", skillId).header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(5)));

        assertThat(queries).as("查询条数不该随版本数增长")
            .isLessThanOrEqualTo(7);
    }

    /**
     * 造 count 个已发布版本（带制品与扫描）。
     *
     * 直接写库，不走对象存储——这里只关心查询条数，不关心包里的内容。
     */
    private void insertPublishedVersions(UUID skillId, int count) {
        for (int i = 1; i <= count; i++) {
            UUID artifactId = UUID.randomUUID();
            jdbc.update("insert into artifacts (id, sha256, storage_key, size_bytes, file_count, "
                + "manifest_json, created_at) values (?, ?, ?, 10, 1, '[]'::jsonb, now())",
                artifactId, "%064d".formatted(i), "test/" + artifactId);
            UUID versionId = UUID.randomUUID();
            jdbc.update("insert into skill_versions (id, skill_id, version, channel, artifact_id, "
                + "manifest_json, status, created_at, published_at) "
                + "values (?, ?, ?, 'stable', ?, '[]'::jsonb, 'published', now(), now())",
                versionId, skillId, "1.0." + i, artifactId);
            jdbc.update("insert into skill_scans (id, skill_version_id, scanner_name, "
                + "scanner_version, status, findings_json, summary, started_at) "
                + "values (?, ?, 'skillmesh-server-scan', '1', 'passed', '[]'::jsonb, 'ok', now())",
                UUID.randomUUID(), versionId);
        }
    }

    /** 发布一个版本并完成上传，返回版本列表里该版本那一项的 JSON。 */
    private String publishVersion(UUID skillId, String version, byte[] zip) throws Exception {
        String created = mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(versionBody(version, zip)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var node = json.readTree(created);
        putToStorage(node.path("upload").path("url").asText(), zip);
        mockMvc.perform(post("/api/v1/uploads/{id}/complete",
                node.path("upload").path("session_id").asText())
                .header("Authorization", auth))
            .andExpect(status().isAccepted());

        String versionsJson = mockMvc.perform(get("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth))
            .andReturn().getResponse().getContentAsString();
        for (var item : json.readTree(versionsJson).path("items")) {
            if (item.path("version").asText().equals(version)) {
                return item.toString();
            }
        }
        throw new AssertionError("版本列表里找不到 " + version);
    }

    private UUID createSkill(String slug) throws Exception {
        String resp = mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", slug, "name", slug,
                    "description", "", "visibility", "private"))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).path("id").asText());
    }

    /** 按可见性建一个 Skill（owner 建）。 */
    private UUID createSkillWithVisibility(String slug, String visibility) throws Exception {
        String resp = mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", slug, "name", slug,
                    "description", "", "visibility", visibility))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).path("id").asText());
    }
    /** 令牌用户（测试里唯一的真实用户），组织里是 owner。 */
    private CurrentUser owner() throws Exception {
        String body = mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).path("user").path("id").asText());
        return new CurrentUser(id, "owner", "Owner", "owner@example.com");
    }

    private UUID createOrg(String slug) throws Exception {
        return orgs.createOrganization(owner(), slug, slug).getId();
    }

    private UUID createTeam(UUID orgId, String slug) throws Exception {
        return orgs.createTeam(owner(), orgId, slug, slug, null).getId();
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

    /**
     * 造一个真实用户行，并给它组织身份与团队身份——接受邀请之后就是这样：
     * 两个身份各有一行，角色分别存在。
     */
    private CurrentUser userWithRoles(UUID orgId, UUID teamId, String orgRole, String teamRole) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, oidc_issuer, oidc_subject, display_name, status) "
            + "values (?, 'https://kc', ?, 'Member', 'active')", id, "mem-" + id);
        jdbc.update("insert into organization_memberships "
            + "(organization_id, user_id, role, joined_at) values (?, ?, ?, now())",
            orgId, id, orgRole);
        jdbc.update("insert into team_memberships (team_id, user_id, role, joined_at) "
            + "values (?, ?, ?, now())", teamId, id, teamRole);
        return new CurrentUser(id, "mem-" + id, "Member", null);
    }

    private CreateVersionRequest versionRequest(String version) throws Exception {
        byte[] zip = zipOf("SKILL.md", "# " + version + "\n");
        String digest = sha256(zip);
        return new CreateVersionRequest(version, "stable", null, null, "",
            zip.length, 1, digest,
            List.of(new CreateVersionRequest.ManifestEntry("SKILL.md", digest, zip.length, false)),
            // 不带基线：普通发布，服务端不校验基线。
            null);
    }

    private void putToStorage(String url, byte[] data) throws Exception {
        HttpResponse<String> put = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isEqualTo(200);
    }

    private String versionBody(String version, byte[] zip) throws Exception {
        return versionBodyWithDigest(version, zip, sha256(zip));
    }

    private String versionBodyWithDigest(String version, byte[] zip, String digest) throws Exception {
        return json.writeValueAsString(Map.of(
            "version", version, "channel", "stable", "size_bytes", zip.length,
            "sha256", digest, "file_count", 1,
            "manifest", List.of(Map.of(
                "path", "SKILL.md", "sha256", digest, "size_bytes", zip.length))));
    }

    static byte[] zipOf(String name, String content) throws Exception {
        return TestArtifacts.zipOf(name, content);
    }

    static String sha256(byte[] data) throws Exception {
        return TestArtifacts.sha256(data);
    }
}
