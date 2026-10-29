package com.skillmesh.api.source;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.skillmesh.api.source.dto.SourceUpdateResultResponse;
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
 * 从 Git 来源拉新版，产出新版本。
 *
 * git 拉取本身 mock 掉——真连 GitHub 会让测试依赖网络。
 * 要验的是拉回来之后的那一段：打包、算清单、建版本、扫描、判定发布还是送审。
 */
@SpringBootTest
class GitSourceUpdateServiceTest {

    private static final String COMMIT = "1234567890abcdef1234567890abcdef12345678";

    @Autowired
    private GitSourceUpdateService updates;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private SkillRepository skills;

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

    /** 独立事务：造的数据要提交出去，别的独立事务才看得见。 */
    private TransactionTemplate committed;

    private CurrentUser owner;
    private UUID skillId;
    private UUID sourceId;
    private Path checkoutDir;

    @BeforeEach
    void setUp() throws Exception {
        committed = new TransactionTemplate(transactionManager);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        checkoutDir = Files.createTempDirectory("skillmesh-git-probe-");
        Files.writeString(checkoutDir.resolve("SKILL.md"), "# probe\n", StandardCharsets.UTF_8);
        when(sources.checkout(anyString(), anyString()))
            .thenReturn(new SourceService.Checkout(checkoutDir, COMMIT));
        committed.executeWithoutResult(status -> {
            owner = newUser();
            skillId = newSkill();
            sourceId = newSource();
        });
    }

    @AfterEach
    void tearDown() {
        committed.executeWithoutResult(status -> {
            jdbc.update("delete from audit_events where target_id = ?", skillId.toString());
            jdbc.update("delete from skill_scans where skill_version_id in "
                + "(select id from skill_versions where skill_id = ?)", skillId);
            jdbc.update("update skills set latest_version = null where id = ?", skillId);
            jdbc.update("delete from skill_versions where skill_id = ?", skillId);
            jdbc.update("delete from artifacts where sha256 in "
                + "(select a.sha256 from artifacts a join skill_versions v "
                + "on v.artifact_id = a.id where v.skill_id = ?)", skillId);
            jdbc.update("delete from skill_sources where skill_id = ?", skillId);
            jdbc.update("delete from skills where id = ?", skillId);
            jdbc.update("delete from users where id = ?", owner.id());
        });
    }

    @Test
    void 拉到的内容变成一个新版本() {
        SourceUpdateResultResponse result = updates.update(owner, skillId);

        assertThat(result.version()).isEqualTo("1.0.0");
        assertThat(result.sourceCommit()).isEqualTo(COMMIT);
        // 私有的 Skill 是属主自己的，不用过审。
        assertThat(result.status()).isEqualTo("published");

        var version = versions.findBySkillIdOrderByCreatedAtDesc(skillId).get(0);
        assertThat(version.getStatus()).isEqualTo("published");
        assertThat(version.getSourceCommit()).isEqualTo(COMMIT);
        // 版本行记着这次是从哪个来源来的。
        assertThat(version.getSourceId()).isEqualTo(sourceId);
    }

    @Test
    void 拉过之后来源记的commit跟着更新() {
        updates.update(owner, skillId);

        assertThat(sourceRepository.findById(sourceId).orElseThrow().getResolvedCommit())
            .isEqualTo(COMMIT);
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

    private UUID newSkill() {
        SkillEntity entity = new SkillEntity();
        entity.setId(UUID.randomUUID());
        entity.setSlug("probe-git-skill-" + UUID.randomUUID());
        entity.setName("探针技能");
        entity.setDescription("测试用");
        entity.setOwnerUserId(owner.id());
        entity.setVisibility("private");
        entity.setStatus("active");
        entity.setTags(new String[0]);
        entity.setMetadataJson(Map.of());
        entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return skills.saveAndFlush(entity).getId();
    }

    private UUID newSource() {
        SourceEntity source = new SourceEntity();
        source.setId(UUID.randomUUID());
        source.setSkillId(skillId);
        source.setSourceType("git");
        source.setProvider("github");
        source.setRepositoryUrl("https://github.com/probe/probe-skill.git");
        source.setRefType("branch");
        source.setRefValue("main");
        source.setResolvedCommit("0000000000000000000000000000000000000000");
        source.setLicenseStatus("unknown");
        source.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        source.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        sourceRepository.saveAndFlush(source);
        return source.getId();
    }
}
