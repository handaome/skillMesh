package com.skillmesh.api.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 已发布版本的内容不可原位替换（设计文档 §4.3 DB-002、§1.5）。
 *
 * 服务层只做「建新版本」，那是约定不是约束——数据库触发器才是最后一道。
 * 断言写在 DDL 层：约束漏了要等到数据脏了才发现。
 */
@SpringBootTest
class PublishedVersionImmutableTest {

    @Autowired
    private JdbcTemplate jdbc;

    private UUID userId;
    private UUID skillId;
    private UUID artifactId;
    private UUID otherArtifactId;
    private UUID versionId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        skillId = UUID.randomUUID();
        artifactId = UUID.randomUUID();
        otherArtifactId = UUID.randomUUID();
        versionId = UUID.randomUUID();

        jdbc.update("insert into users (id, display_name, status) values (?, ?, 'active')",
            userId, "探针用户");
        jdbc.update("insert into skills (id, slug, name, owner_user_id) values (?, ?, ?, ?)",
            skillId, "immutable-probe-" + skillId, "探针", userId);
        insertArtifact(artifactId, "sha256/" + artifactId + ".zip");
        insertArtifact(otherArtifactId, "sha256/" + otherArtifactId + ".zip");
        jdbc.update("insert into skill_versions "
                + "(id, skill_id, version, artifact_id, status, published_at) "
                + "values (?, ?, '1.0.0', ?, 'published', now())",
            versionId, skillId, artifactId);
    }

    private void insertArtifact(UUID id, String key) {
        // sha256 有唯一约束：拿 uuid 的十六进制凑两遍，正好 64 位且互不相同。
        String hex = id.toString().replace("-", "");
        jdbc.update("insert into artifacts (id, sha256, storage_key, size_bytes, file_count) "
                + "values (?, ?, ?, 1, 1)", id, hex + hex, key);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from skill_versions where skill_id = ?", skillId);
        jdbc.update("update skills set latest_version = null where id = ?", skillId);
        jdbc.update("delete from skills where id = ?", skillId);
        jdbc.update("delete from artifacts where id in (?, ?)", artifactId, otherArtifactId);
        jdbc.update("delete from users where id = ?", userId);
    }

    @Test
    void 改了已发布版本的制品指向就被数据库挡住() {
        assertThatThrownBy(() -> jdbc.update(
                "update skill_versions set artifact_id = ? where id = ?", otherArtifactId, versionId))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("published skill version content is immutable");
    }

    @Test
    void 撤回状态照常能改() {
        jdbc.update("update skill_versions set status = 'revoked', revoked_at = now() "
            + "where id = ?", versionId);
        assertThat(jdbc.queryForObject("select status from skill_versions where id = ?",
            String.class, versionId)).isEqualTo("revoked");
    }

    @Test
    void 草稿版本随便改() {
        UUID draftId = UUID.randomUUID();
        jdbc.update("insert into skill_versions (id, skill_id, version, artifact_id, status) "
            + "values (?, ?, '0.0.1', ?, 'draft')", draftId, skillId, artifactId);
        try {
            jdbc.update("update skill_versions set artifact_id = ? where id = ?",
                otherArtifactId, draftId);
            assertThat(jdbc.queryForObject("select artifact_id from skill_versions where id = ?",
                UUID.class, draftId)).isEqualTo(otherArtifactId);
        } finally {
            jdbc.update("delete from skill_versions where id = ?", draftId);
        }
    }
}
