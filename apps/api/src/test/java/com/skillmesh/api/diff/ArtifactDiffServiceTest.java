package com.skillmesh.api.diff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.artifact.ArtifactEntity;
import com.skillmesh.api.artifact.ArtifactRepository;
import com.skillmesh.api.artifact.ArtifactStorage;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.support.DatabaseCleaner;
import com.skillmesh.api.support.TestArtifacts;
import com.skillmesh.api.version.SkillVersionEntity;
import com.skillmesh.api.version.SkillVersionRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 版本间的文件级差异：基线选取、manifest 比对、行数统计。
 *
 * 口径对齐 CLI 的 update --dry-run（设计文档 §6）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ArtifactDiffServiceTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;
    @Autowired private ArtifactDiffService diffs;
    @Autowired private SkillVersionRepository versions;
    @Autowired private ArtifactRepository artifacts;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private DataSource dataSource;

    /** 制品读取的探针——用来看下载那一刻有没有事务。 */
    @MockitoSpyBean
    private ArtifactStorage storage;

    private String auth;

    @BeforeEach
    void 准备() throws Exception {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
    }

    @Test
    void 首个版本没有基线全部算新增() throws Exception {
        UUID skillId = createSkill("first");
        publish(skillId, "1.0.0", Map.of("SKILL.md", "# Runbook\n"));

        ArtifactDiffResult result = diffs.diffAgainstLatestPublished(
            skillId, version(skillId, "1.0.0"));

        assertThat(result.baseVersion()).isEmpty();
        assertThat(result.files()).hasSize(1);
        assertThat(result.files().get(0).path()).isEqualTo("SKILL.md");
        assertThat(result.files().get(0).kind()).isEqualTo("added");
    }

    @Test
    void 跟最近的已发布版本比() throws Exception {
        UUID skillId = createSkill("second");
        publish(skillId, "1.0.0", Map.of("SKILL.md", "# Runbook\n"));
        publish(skillId, "1.1.0", Map.of("SKILL.md", "# Runbook v2\n"));

        ArtifactDiffResult result = diffs.diffAgainstLatestPublished(
            skillId, version(skillId, "1.1.0"));

        assertThat(result.baseVersion()).isEqualTo("1.0.0");
        assertThat(result.files()).hasSize(1);
        FileChange change = result.files().get(0);
        assertThat(change.path()).isEqualTo("SKILL.md");
        assertThat(change.kind()).isEqualTo("modified");
        assertThat(change.added()).isEqualTo(1);
        assertThat(change.removed()).isEqualTo(1);
        assertThat(change.truncated()).isFalse();
    }

    @Test
    void 新增与删除的文件都认得出() throws Exception {
        UUID skillId = createSkill("third");
        publish(skillId, "1.0.0", Map.of("SKILL.md", "# Runbook\n", "old.md", "lao\n"));
        publish(skillId, "1.1.0", Map.of("SKILL.md", "# Runbook\n", "new.md", "xin\n"));

        ArtifactDiffResult result = diffs.diffAgainstLatestPublished(
            skillId, version(skillId, "1.1.0"));

        assertThat(result.files()).extracting(FileChange::path)
            .containsExactly("new.md", "old.md");
        assertThat(result.files()).extracting(FileChange::kind)
            .containsExactly("added", "removed");
    }

    @Test
    void 内容一样就看不出改动() throws Exception {
        UUID skillId = createSkill("same");
        publish(skillId, "1.0.0", Map.of("SKILL.md", "# Runbook\n"));
        publish(skillId, "1.1.0", Map.of("SKILL.md", "# Runbook\n"));

        ArtifactDiffResult result = diffs.diffAgainstLatestPublished(
            skillId, version(skillId, "1.1.0"));

        assertThat(result.baseVersion()).isEqualTo("1.0.0");
        assertThat(result.files()).isEmpty();
    }

    @Test
    void 二进制文件只报文件级() throws Exception {
        UUID skillId = createSkill("binary");
        publish(skillId, "1.0.0", Map.of("a.bin", "old\u0000data"));
        publish(skillId, "1.1.0", Map.of("a.bin", "new\u0000bytes"));

        ArtifactDiffResult result = diffs.diffAgainstLatestPublished(
            skillId, version(skillId, "1.1.0"));

        assertThat(result.files()).hasSize(1);
        FileChange change = result.files().get(0);
        assertThat(change.kind()).isEqualTo("modified");
        assertThat(change.truncated()).isTrue();
        assertThat(change.added()).isZero();
        assertThat(change.removed()).isZero();
    }

    @Test
    void 读得出SKILL正文() throws Exception {
        UUID skillId = createSkill("markdown");
        publish(skillId, "1.0.0", Map.of("SKILL.md", "# Runbook\n正文在这里\n"));

        String markdown = diffs.readSkillMarkdown(artifactOf(skillId, "1.0.0"));

        assertThat(markdown).contains("正文在这里");
    }

    @Test
    void 没有制品时不报错() throws Exception {
        UUID skillId = createSkill("no-artifact");
        // 只建版本不上传——版本还在 draft，没有制品。
        SkillVersionEntity version = createDraft(skillId, "1.0.0");

        ArtifactDiffResult result = diffs.diffAgainstLatestPublished(skillId, version);

        assertThat(result.files()).isEmpty();
    }

    /**
     * 基线候选里清单为空的已发布版本要跳过。
     *
     * 拿它当基线的话整份 diff 会退化成「全部 added」，而调用方分不清
     * 「没有基线」和「基线清单是空的」。
     */
    @Test
    void 基线跳过清单为空的已发布版本() throws Exception {
        UUID skillId = createSkill("empty-base");
        publish(skillId, "1.0.0", Map.of("SKILL.md", "# Runbook\n"));
        // 中间这个版本发布了但包里一个文件都没有——服务端算出的清单是空的，
        // 不能拿它当基线。
        publishEmpty(skillId, "1.1.0");
        publish(skillId, "1.2.0", Map.of("SKILL.md", "# Runbook v2\n"));

        ArtifactDiffResult result = diffs.diffAgainstLatestPublished(
            skillId, version(skillId, "1.2.0"));

        assertThat(result.baseVersion()).isEqualTo("1.0.0");
        assertThat(result.files()).singleElement()
            .extracting(FileChange::kind).isEqualTo("modified");
    }

    /**
     * 声明的清单不可信：diff 要按服务端从真 zip 算的清单比。
     *
     * 发布方在建版本请求里自填 manifest——那只是他的说法。1.1.0 声明「没变」，
     * 真包里 SKILL.md 却换了内容：diff 必须报出改动（读声明的话会报「无差异」，
     * 审核者就信了）。
     */
    @Test
    void 声明的清单不可信要按真包算() throws Exception {
        UUID skillId = createSkill("lying");
        publish(skillId, "1.0.0", Map.of("SKILL.md", "# A\n"), manifestOf("# A\n"));
        // 声明写「没变」（还是 A 的摘要），真包里换成了 B
        publish(skillId, "1.1.0", Map.of("SKILL.md", "# B\n"), manifestOf("# A\n"));

        ArtifactDiffResult changed = diffs.diffAgainstLatestPublished(
            skillId, version(skillId, "1.1.0"));

        assertThat(changed.baseVersion()).isEqualTo("1.0.0");
        assertThat(changed.files()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("SKILL.md");
            assertThat(change.kind()).isEqualTo("modified");
        });

        // 反过来：真包没变（声明写着别的）就该报「无差异」
        publish(skillId, "1.2.0", Map.of("SKILL.md", "# B\n"), manifestOf("# C\n"));

        ArtifactDiffResult same = diffs.diffAgainstLatestPublished(
            skillId, version(skillId, "1.2.0"));

        assertThat(same.files()).isEmpty();
    }

    /**
     * 增删之和恰好 200 行不算超限，201 行才算。
     *
     * 超限时仍然照给真实数字——CLI 是 {@code return added, removed, added+removed > maxDiffLines}。
     */
    @Test
    void 两百行整与两百零一行的分界() throws Exception {
        UUID exact = createSkill("lines-200");
        publish(exact, "1.0.0", Map.of("SKILL.md", lines(100, "old")));
        publish(exact, "1.1.0", Map.of("SKILL.md", lines(100, "new")));

        FileChange atLimit = diffs.diffAgainstLatestPublished(exact, version(exact, "1.1.0"))
            .files().get(0);
        assertThat(atLimit.added()).isEqualTo(100);
        assertThat(atLimit.removed()).isEqualTo(100);
        assertThat(atLimit.truncated()).isFalse();

        UUID over = createSkill("lines-201");
        publish(over, "1.0.0", Map.of("SKILL.md", lines(101, "old")));
        publish(over, "1.1.0", Map.of("SKILL.md", lines(100, "new")));

        FileChange overLimit = diffs.diffAgainstLatestPublished(over, version(over, "1.1.0"))
            .files().get(0);
        assertThat(overLimit.added()).isEqualTo(100);
        assertThat(overLimit.removed()).isEqualTo(101);
        assertThat(overLimit.truncated()).isTrue();
    }

    /**
     * 下载 zip 时不能占着数据库连接。
     *
     * 审核队列的 {@code detail} 是 {@code @Transactional}，差异计算要下载两个 zip——
     * 事务期间占着连接，并发看审核页会把连接池占满。
     */
    @Test
    void 下载制品时不持有数据库连接() throws Exception {
        UUID skillId = createSkill("tx");
        publish(skillId, "1.0.0", Map.of("SKILL.md", "# A\n"));
        publish(skillId, "1.1.0", Map.of("SKILL.md", "# B\n"));

        List<Boolean> txDuringRead = new ArrayList<>();
        List<Object> connectionDuringRead = new ArrayList<>();
        AtomicBoolean recording = new AtomicBoolean();
        doAnswer(invocation -> {
            if (recording.get()) {
                txDuringRead.add(TransactionSynchronizationManager.isActualTransactionActive());
                connectionDuringRead.add(
                    TransactionSynchronizationManager.getResource(dataSource));
            }
            return invocation.callRealMethod();
        }).when(storage).get(Mockito.anyString());

        recording.set(true);
        // 在事务里调用——审核队列就是这种情形
        new TransactionTemplate(txManager).execute(status ->
            diffs.diffAgainstLatestPublished(skillId, version(skillId, "1.1.0")));
        recording.set(false);

        assertThat(txDuringRead).isNotEmpty();
        assertThat(txDuringRead).containsOnly(false);
        // 连接也没绑在这个线程上——不是「事务挂起了但连接还攥着」
        assertThat(connectionDuringRead).containsOnlyNulls();
    }

    /** 建一个 Skill（个人可见，版本发出去即 published）。 */
    private UUID createSkill(String slug) throws Exception {
        String resp = mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", slug, "name", slug,
                    "description", "", "visibility", "private"))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).path("id").asText());
    }

    /** 建版本 → 直传 → 收尾，走真实的上传链路。 */
    private void publish(UUID skillId, String version, Map<String, String> files) throws Exception {
        List<Map<String, Object>> manifest = new ArrayList<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            byte[] content = file.getValue().getBytes(StandardCharsets.UTF_8);
            manifest.add(Map.<String, Object>of("path", file.getKey(),
                "sha256", TestArtifacts.sha256(content), "size_bytes", (long) content.length));
        }
        publish(skillId, version, files, manifest);
    }

    /** 同上，但清单由调用方给——用来造「声明的清单跟真包对不上」的版本。 */
    private void publish(UUID skillId, String version, Map<String, String> files,
                        List<Map<String, Object>> manifest) throws Exception {
        upload(skillId, version, TestArtifacts.zipOf(files), manifest, files.size());
    }

    /** 传一个空包：包里没有文件，服务端算出来的清单是空的。 */
    private void publishEmpty(UUID skillId, String version) throws Exception {
        upload(skillId, version, TestArtifacts.zipOf(Map.of()), List.of(), 1);
    }

    /** 造一条声明的清单项：路径 SKILL.md，摘要按给的内容算。 */
    private List<Map<String, Object>> manifestOf(String content) throws Exception {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        return List.of(Map.of("path", "SKILL.md", "sha256", TestArtifacts.sha256(data),
            "size_bytes", (long) data.length));
    }

    /** 建版本 → 直传 → 收尾，走真实的上传链路。 */
    private void upload(UUID skillId, String version, byte[] zip,
                        List<Map<String, Object>> manifest, int fileCount) throws Exception {
        String body = json.writeValueAsString(Map.of(
            "version", version, "channel", "stable", "size_bytes", zip.length,
            "sha256", TestArtifacts.sha256(zip), "file_count", Math.max(1, fileCount),
            "manifest", manifest));
        String created = mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var node = json.readTree(created);
        HttpResponse<String> put = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(node.path("upload").path("url").asText()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(zip)).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(put.statusCode()).isEqualTo(200);
        mockMvc.perform(post("/api/v1/uploads/{id}/complete",
                node.path("upload").path("session_id").asText())
                .header("Authorization", auth))
            .andExpect(status().isAccepted());
    }

    private SkillVersionEntity version(UUID skillId, String version) {
        return versions.findBySkillIdAndVersion(skillId, version).orElseThrow();
    }

    /** 只建版本不上传——版本停在 draft，没有制品。 */
    private SkillVersionEntity createDraft(UUID skillId, String version) throws Exception {
        byte[] zip = TestArtifacts.zipOf(Map.of("SKILL.md", "# Runbook\n"));
        String body = json.writeValueAsString(Map.of(
            "version", version, "channel", "stable", "size_bytes", zip.length,
            "sha256", TestArtifacts.sha256(zip), "file_count", 1,
            "manifest", List.of(Map.of("path", "SKILL.md",
                "sha256", TestArtifacts.sha256(zip), "size_bytes", (long) zip.length))));
        mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());
        return version(skillId, version);
    }

    private ArtifactEntity artifactOf(UUID skillId, String version) {
        return artifacts.findById(version(skillId, version).getArtifactId()).orElse(null);
    }

    /**
     * n 行正文，每行内容互不相同。
     *
     * 行间用 n-1 个换行符——末尾不带换行，否则切出来是 n+1 行。
     */
    private static String lines(int count, String tag) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(tag).append('-').append(i);
        }
        return sb.toString();
    }
}
