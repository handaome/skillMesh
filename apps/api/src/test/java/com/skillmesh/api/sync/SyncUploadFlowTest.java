package com.skillmesh.api.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.device.DeviceService;
import com.skillmesh.api.org.OrgService;
import com.skillmesh.api.support.DatabaseCleaner;
import com.skillmesh.api.sync.dto.SyncChangesetRequest;
import com.skillmesh.api.sync.dto.SyncChangesetResponse;
import com.skillmesh.api.upload.UploadService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * 文件级同步：开上传会话 → 逐个上传 → commit（详细设计 §2.4.2）。
 *
 * 关键点：commit 时云端基线对不上必须转冲突，不能默默覆盖别人的内容。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SyncUploadFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;
    @Autowired private OrgService orgs;
    @Autowired private DeviceService devices;
    @Autowired private SyncService sync;
    @Autowired private SyncUploadService syncUploads;
    @Autowired private UploadService uploads;
    @Autowired private JdbcTemplate jdbc;

    private String auth;

    @BeforeEach
    void 准备() throws Exception {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
    }

    @Test
    void 传完提交生成新版本() throws Exception {
        UUID skillId = createSkill();
        String deviceId = deviceId();
        String changesetId = createChangeset(skillId, deviceId, "", contentHash("v1"));

        byte[] skillMd = "SKILL.md".getBytes(StandardCharsets.UTF_8);
        byte[] notes = "notes".getBytes(StandardCharsets.UTF_8);
        String slots = openUploads(changesetId,
            List.of(fileSpec("SKILL.md", skillMd), fileSpec("references/notes.md", notes)));
        var parsed = json.readTree(slots);
        putFile(parsed.get(0).path("upload_url").asText(), skillMd);
        putFile(parsed.get(1).path("upload_url").asText(), notes);
        for (var slot : parsed) {
            mockMvc.perform(post("/api/v1/uploads/{id}/complete", slot.path("upload_id").asText())
                    .header("Authorization", auth))
                .andExpect(status().isAccepted());
        }

        String committed = mockMvc.perform(
                    post("/api/v1/sync/changesets/{id}/commit", changesetId)
                        .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_revision\":\"\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(committed).path("status").asText()).isEqualTo("completed");
        assertThat(json.readTree(committed).path("files").asInt()).isEqualTo(2);

        // 新版本已经可安装：内容摘要和服务端 manifest 对得上
        String versions = mockMvc.perform(get("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(versions).path("items").get(0).path("status").asText())
            .isEqualTo("published");
    }

    @Test
    void 基线对不上就转冲突不覆盖() throws Exception {
        UUID skillId = createSkill();
        String deviceId = deviceId();

        // 先正常提交一版，云端就有了内容摘要
        String first = createChangeset(skillId, deviceId, "", contentHash("v1"));
        uploadOne(first, "SKILL.md", "SKILL.md".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/api/v1/sync/changesets/{id}/commit", first)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isOk());

        // 再提交一次，但期望的基线是旧的 → 冲突
        String second = createChangeset(skillId, deviceId, "0".repeat(64), contentHash("v2"));
        uploadOne(second, "SKILL.md", "别的内容".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(post("/api/v1/sync/changesets/{id}/commit", second)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expected_revision\":\"" + "0".repeat(64) + "\"}"))
            .andExpect(status().isConflict());

        // 云端仍是第一版
        String versions = mockMvc.perform(get("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(versions).path("items").size()).isEqualTo(1);
    }

    @Test
    void 相对路径越界要拒绝() throws Exception {
        UUID skillId = createSkill();
        String changesetId = createChangeset(skillId, deviceId(), "", contentHash("v1"));
        mockMvc.perform(post("/api/v1/sync/changesets/{id}/uploads", changesetId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(List.of(
                    Map.of("path", "../逃逸.txt", "sha256", "a".repeat(64),
                        "size_bytes", 1)))))
            .andExpect(status().isBadRequest());
    }

    /**
     * 团队可见的 Skill 走同步提交也要进审核队列。
     *
     * 同步路径原来直接把版本置成 published，等于绕开了审核队列：
     * 团队里没人知道这一版内容进了组织。
     */
    @Test
    void 团队可见的Skill同步后要等人审() throws Exception {
        CurrentUser owner = currentUser();
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = createTeamSkill(orgId, teamId);

        String changesetId = createChangeset(skillId, deviceId(), "", contentHash("v1"));
        uploadOne(changesetId, "SKILL.md", "SKILL.md".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/api/v1/sync/changesets/{id}/commit", changesetId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expected_revision\":\"\"}"))
            .andExpect(status().isOk());

        String versions = mockMvc.perform(get("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(versions).path("items").get(0).path("status").asText())
            .as("同步提交不能绕过审核队列").isEqualTo("pending_review");
    }

    /**
     * 组织普通成员不能靠同步提交往团队的待审队列里塞版本。
     *
     * 整包上传路径有发布权那道闸（组织角色 publisher 及以上），
     * sync 路径原来只查 canView——两条产出队列的路径口径不一致。
     */
    @Test
    void 组织普通成员不能同步提交团队Skill() throws Exception {
        CurrentUser owner = currentUser();
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = createTeamSkill(orgId, teamId);

        // 组织角色只是 member 的另一个人（团队里也是 member）
        CurrentUser member = memberOf(owner, orgId, teamId);
        UUID deviceId = devices.register(member, "member-device", null, null, null).getId();
        SyncChangesetResponse changeset = sync.submit(member, deviceId,
            UUID.randomUUID().toString(),
            new SyncChangesetRequest(skillId, "upload", "", contentHash("v1"), null, List.of()));

        byte[] content = "SKILL.md".getBytes(StandardCharsets.UTF_8);
        List<SyncUploadService.UploadSlot> slots = syncUploads.openUploads(member,
            changeset.changesetId(),
            List.of(new SyncUploadService.FileSpec("SKILL.md", sha256Hex(content),
                content.length, "text/plain")));
        putFile(slots.get(0).uploadUrl(), content);
        uploads.complete(member, UUID.fromString(slots.get(0).uploadId()));

        ApiException error = catchThrowableOfType(
            () -> syncUploads.commit(member, changeset.changesetId(), ""), ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.status()).isEqualTo(403);
    }

    /** 造一个组织角色与团队角色都只是 member 的人。 */
    private CurrentUser memberOf(CurrentUser owner, UUID orgId, UUID teamId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, oidc_issuer, oidc_subject, display_name, status) "
            + "values (?, 'https://kc', ?, 'Member', 'active')", id, "mem-" + id);
        orgs.addOrgMember(owner, orgId, id, "member");
        orgs.addTeamMember(owner, teamId, id, "member");
        return new CurrentUser(id, "mem-" + id, "Member", null);
    }

    /** 开一个文件的上传会话、传上去、complete 走一遍。 */
    private void uploadOne(String changesetId, String path, byte[] content) throws Exception {
        String slots = openUploads(changesetId, List.of(fileSpec(path, content)));
        var slot = json.readTree(slots).get(0);
        putFile(slot.path("upload_url").asText(), content);
        mockMvc.perform(post("/api/v1/uploads/{id}/complete", slot.path("upload_id").asText())
                .header("Authorization", auth))
            .andExpect(status().isAccepted());
    }

    private Map<String, Object> fileSpec(String path, byte[] content) {
        return Map.of("path", path, "sha256", sha256Hex(content),
            "size_bytes", content.length, "content_type", "text/plain");
    }

    private String openUploads(String changesetId, List<Map<String, Object>> specs)
            throws Exception {
        return mockMvc.perform(post("/api/v1/sync/changesets/{id}/uploads", changesetId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(specs)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private void putFile(String url, byte[] content) throws Exception {
        HttpResponse<String> put = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isEqualTo(200);
    }

    private String createChangeset(UUID skillId, String deviceId, String baseHash,
                                  String localHash) throws Exception {
        String body = mockMvc.perform(post("/api/v1/sync/changesets")
                .header("Authorization", auth)
                .header("X-Device-Id", deviceId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "skill_id", skillId.toString(),
                    "direction", "upload",
                    "base_hash", baseHash,
                    "local_hash", localHash,
                    "manifest", List.of()))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("changeset_id").asText();
    }

    private String deviceId() throws Exception {
        String body = mockMvc.perform(post("/api/v1/devices").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_key\":\"sync-device\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("id").asText();
    }

    private UUID createSkill() throws Exception {
        String body = mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", "synced", "name", "synced",
                    "description", "", "visibility", "private"))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("id").asText());
    }

    /** 团队可见的 Skill：要走审核队列的那种。 */
    private UUID createTeamSkill(UUID orgId, UUID teamId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "slug", "synced-team", "name", "synced-team", "description", "",
                    "visibility", "team",
                    "organization_id", orgId.toString(),
                    "owner_team_id", teamId.toString()))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("id").asText());
    }

    /** 测试令牌背后的那个人——建组织要他的身份。 */
    private CurrentUser currentUser() throws Exception {
        String body = mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).path("user").path("id").asText());
        return new CurrentUser(id, "owner", "Owner", "owner@example.com");
    }

    private static String sha256Hex(byte[] content) {
        return contentHash(new String(content, StandardCharsets.UTF_8));
    }

    /** 任意一个合法的 64 位摘要：这个字段只用来做版本之间的对比。 */
    private static String contentHash(String seed) {
        try {
            return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(
                    seed.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
