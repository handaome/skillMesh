package com.skillmesh.api.diff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.artifact.ArtifactEntity;
import com.skillmesh.api.artifact.ArtifactRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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
        byte[] zip = TestArtifacts.zipOf(files);
        List<Map<String, Object>> manifest = new ArrayList<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            byte[] content = file.getValue().getBytes(StandardCharsets.UTF_8);
            manifest.add(Map.<String, Object>of("path", file.getKey(),
                "sha256", TestArtifacts.sha256(content), "size_bytes", (long) content.length));
        }
        String body = json.writeValueAsString(Map.of(
            "version", version, "channel", "stable", "size_bytes", zip.length,
            "sha256", TestArtifacts.sha256(zip), "file_count", files.size(),
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
}
