package com.skillmesh.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import com.skillmesh.api.support.DatabaseCleaner;

/**
 * 迁移落库验证：表建全了、唯一约束和不可变约束真的生效。
 * 断言写在 DDL 层，而不是只测应用行为——约束漏了要等到数据脏了才发现。
 */
@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleaner cleaner;

    private int artifactCounter;

    @BeforeEach
    void 清库() {
        cleaner.clean();
    }

    @Test
    void 迁移建出全部19张表() {
        List<String> tables = jdbc.queryForList(
            "select table_name from information_schema.tables "
                + "where table_schema = 'skillmesh_test' order by table_name",
            String.class);
        assertThat(tables).containsExactlyInAnyOrder(
            "users", "organizations", "organization_memberships", "teams", "team_memberships",
            "skills", "skill_sources", "artifacts", "skill_versions", "skill_scans",
            "skill_reviews", "skill_permissions", "devices", "installations",
            "sync_states", "sync_changesets", "sync_conflicts", "upload_sessions",
            "audit_events");
    }

    @Test
    void 同一用户下slug唯一() {
        String userId = insertUser("u-1");
        jdbc.execute("insert into skills (slug, name, owner_user_id) "
            + "values ('demo', 'Demo', " + userId + ")");

        assertThatThrownBy(() -> jdbc.execute(
            "insert into skills (slug, name, owner_user_id) values ('demo', 'Demo2', " + userId + ")"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 同一Skill下版本号唯一且发布后不可覆盖() {
        String userId = insertUser("u-1");
        String skillId = insertSkill(userId, "demo");
        insertVersion(skillId, "1.0.0", "published");

        assertThatThrownBy(() -> insertVersion(skillId, "1.0.0", "published"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 发布版本必须带制品() {
        String userId = insertUser("u-1");
        String skillId = insertSkill(userId, "demo");

        assertThatThrownBy(() -> jdbc.execute(
            "insert into skill_versions (skill_id, version, channel, status, published_at) "
                + "values (" + skillId + ", '2.0.0', 'stable', 'published', now())"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 制品摘要唯一() {
        String digest = "a".repeat(64);
        jdbc.execute("insert into artifacts (sha256, storage_key, content_type, size_bytes, file_count) "
            + "values ('" + digest + "', 'k/1', 'application/zip', 10, 1)");

        assertThatThrownBy(() -> jdbc.execute(
            "insert into artifacts (sha256, storage_key, content_type, size_bytes, file_count) "
                + "values ('" + digest + "', 'k/2', 'application/zip', 10, 1)"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private String insertUser(String subject) {
        jdbc.execute("insert into users (oidc_issuer, oidc_subject, display_name) "
            + "values ('https://kc', '" + subject + "', 'User " + subject + "')");
        return jdbc.queryForObject(
            "select id from users where oidc_subject = ?", String.class, subject);
    }

    private String insertSkill(String userId, String slug) {
        jdbc.execute("insert into skills (slug, name, owner_user_id) "
            + "values ('" + slug + "', '" + slug + "', " + userId + ")");
        return jdbc.queryForObject(
            "select id from skills where slug = ?", String.class, slug);
    }

    private void insertVersion(String skillId, String version, String status) {
        String digest = nextDigest();
        String artifactId = jdbc.queryForObject(
            "insert into artifacts (sha256, storage_key, content_type, size_bytes, file_count) "
                + "values ('" + digest + "', 'k/" + digest + "', 'application/zip', 10, 1) returning id",
            String.class);
        jdbc.execute("insert into skill_versions "
                + "(skill_id, version, channel, status, artifact_id, published_at) values ("
                + skillId + ", '" + version + "', 'stable', '" + status + "', " + artifactId
                + ", now())");
    }

    /** 每次调用给出不同的 64 位十六进制摘要，避免先撞上 artifacts 的唯一约束。 */
    private String nextDigest() {
        artifactCounter++;
        return String.format("%064x", artifactCounter);
    }
}
