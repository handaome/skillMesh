package com.skillmesh.api.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.skillmesh.api.artifact.ArtifactRepository;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.UserEntity;
import com.skillmesh.api.auth.UserRepository;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SourceEntity;
import com.skillmesh.api.skill.SourceRepository;
import com.skillmesh.api.source.dto.ImportFromGitBatchRequest;
import com.skillmesh.api.source.dto.ImportFromGitBatchResponse;
import com.skillmesh.api.source.dto.ImportFromGitRequest;
import com.skillmesh.api.source.dto.ImportFromGitResponse;
import com.skillmesh.api.version.SkillVersionRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 从 Git 导入一个 Skill。
 *
 * git 拉取本身 mock 掉——真连 GitHub 会让测试依赖网络。
 * 要验的是拉回来之后的那一段：建 Skill、记来源、产出首个版本。
 */
@SpringBootTest
class GitSourceImportServiceTest {

    private static final String COMMIT = "abcdef1234567890abcdef1234567890abcdef12";

    @Autowired
    private GitSourceImportService imports;

    @Autowired
    private SkillRepository skills;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private SkillVersionRepository versions;

    @Autowired
    private ArtifactRepository artifacts;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private SourceService sources;

    private TransactionTemplate committed;

    private CurrentUser owner;
    private UUID skillId;

    @BeforeEach
    void setUp() throws Exception {
        committed = new TransactionTemplate(transactionManager);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Path checkout = Files.createTempDirectory("skillmesh-import-probe-");
        Path skillDir = Files.createDirectories(checkout.resolve("skills/probe"));
        // frontmatter 要合规：不符合 agentskills.io 规范的制品会被扫描拦成隔离态。
        Files.writeString(skillDir.resolve("SKILL.md"),
            "---\nname: probe\ndescription: 探针技能\n---\n\n# probe\n",
            StandardCharsets.UTF_8);
        Path other = Files.createDirectories(checkout.resolve("skills/other"));
        Files.writeString(other.resolve("SKILL.md"),
            "---\nname: other\ndescription: 另一个\n---\n\n# other\n",
            StandardCharsets.UTF_8);
        when(sources.checkout(anyString(), anyString()))
            .thenReturn(new SourceService.Checkout(checkout, COMMIT));
        when(sources.refType(any(Path.class), anyString())).thenReturn("branch");
        committed.executeWithoutResult(status -> owner = newUser());
    }

    @AfterEach
    void tearDown() {
        committed.executeWithoutResult(status -> {
            jdbc.update("delete from audit_events where target_id in "
                + "(select id::text from skills where owner_user_id = ?)", owner.id());
            jdbc.update("delete from skill_scans where skill_version_id in "
                + "(select id from skill_versions where skill_id in "
                + "(select id from skills where owner_user_id = ?))", owner.id());
            jdbc.update("update skills set latest_version = null where owner_user_id = ?",
                owner.id());
            jdbc.update("delete from skill_versions where skill_id in "
                + "(select id from skills where owner_user_id = ?)", owner.id());
            jdbc.update("delete from skill_sources where skill_id in "
                + "(select id from skills where owner_user_id = ?)", owner.id());
            jdbc.update("delete from artifacts where sha256 in "
                + "(select a.sha256 from artifacts a join skill_versions v "
                + "on v.artifact_id = a.id join skills s on s.id = v.skill_id "
                + "where s.owner_user_id = ?)", owner.id());
            jdbc.update("delete from skills where owner_user_id = ?", owner.id());
            jdbc.update("delete from users where id = ?", owner.id());
        });
    }

    @Test
    void 导入后有来源记录和首个版本() {
        ImportFromGitResponse result = imports.importSkill(owner, request());

        assertThat(result.slug()).isEqualTo("probe-skill");
        assertThat(result.version()).isEqualTo("1.0.0");
        // 私有 Skill 是属主自己的，不用过审。
        assertThat(result.status()).isEqualTo("published");
        assertThat(result.sourceCommit()).isEqualTo(COMMIT);

        skillId = UUID.fromString(result.skillId());
        List<SourceEntity> sources = sourceRepository.findBySkillIdOrderByCreatedAtDesc(skillId);
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).getSourceType()).isEqualTo("git");
        assertThat(sources.get(0).getRepositoryUrl())
            .isEqualTo("https://github.com/probe/probe-skill.git");
        // 拉过之后来源记的就是这个 commit：下次「检查更新」拿它跟远端比。
        assertThat(sources.get(0).getResolvedCommit()).isEqualTo(COMMIT);
        assertThat(sources.get(0).getRefType()).isEqualTo("branch");

        var version = versions.findBySkillIdOrderByCreatedAtDesc(skillId).get(0);
        assertThat(version.getStatus()).isEqualTo("published");
        assertThat(version.getSourceCommit()).isEqualTo(COMMIT);
    }

    private ImportFromGitRequest request() {
        return new ImportFromGitRequest("https://github.com/probe/probe-skill.git",
            "skills/probe", "main", "probe-skill", "探针技能", "测试用",
            "private", List.of());
    }

    @Test
    void 批量导入逐条独立() {
        ImportFromGitBatchResponse result = imports.importBatch(owner,
            new ImportFromGitBatchRequest("https://github.com/probe/probe-skill.git",
                List.of("skills/probe", "skills/other", "skills/missing"),
                "main", "private", List.of()));

        // 两条导进来：slug 取目录名，名字取 frontmatter 里的声明。
        assertThat(result.imported()).hasSize(2);
        assertThat(result.imported()).extracting(ImportFromGitBatchResponse.Imported::slug)
            .containsExactlyInAnyOrder("probe", "other");
        assertThat(result.imported())
            .allSatisfy(item -> assertThat(item.status()).isEqualTo("published"));
        // 找不到的那条单独算失败，不牵连别的。
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).path()).isEqualTo("skills/missing");
        assertThat(result.failed().get(0).reason()).contains("找不到 Skill 目录");
    }

    private CurrentUser newUser() {
        UUID id = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setOidcIssuer("https://probe.local");
        user.setOidcSubject("probe-" + id);
        user.setDisplayName("探针属主");
        user.setStatus("active");
        user.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        user.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        users.saveAndFlush(user);
        return new CurrentUser(id, user.getOidcSubject(), user.getDisplayName(), null, "active", "user");
    }
}
