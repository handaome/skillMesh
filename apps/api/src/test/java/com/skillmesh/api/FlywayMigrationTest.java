package com.skillmesh.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
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
    void 迁移建出全部20张表() {
        List<String> tables = jdbc.queryForList(
            "select table_name from information_schema.tables "
                + "where table_schema = current_schema() and table_name not like 'flyway%' "
                + "order by table_name",
            String.class);
        assertThat(tables).containsExactlyInAnyOrder(
            "users", "organizations", "organization_memberships", "teams", "team_memberships",
            "skills", "skill_sources", "artifacts", "skill_versions", "skill_scans",
            "skill_reviews", "devices", "installations",
            "sync_states", "sync_changesets", "sync_conflicts", "upload_sessions",
            "audit_events", "org_invitations", "skill_recycle_files");
        assertThat(tables).hasSize(20);
    }

    @Test
    void 同一用户下slug唯一() {
        UUID userId = insertUser("u-1");
        insertSkill(userId, "demo");

        assertThatThrownBy(() -> insertSkill(userId, "demo"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 同一Skill下版本号唯一且发布后不可覆盖() {
        UUID userId = insertUser("u-1");
        UUID skillId = insertSkill(userId, "demo");
        insertVersion(skillId, "1.0.0", "published");

        assertThatThrownBy(() -> insertVersion(skillId, "1.0.0", "published"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 发布版本必须带制品() {
        UUID userId = insertUser("u-1");
        UUID skillId = insertSkill(userId, "demo");

        assertThatThrownBy(() -> jdbc.update(
            "insert into skill_versions (skill_id, version, channel, status, published_at) "
                + "values (?, '2.0.0', 'stable', 'published', now())", skillId))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 制品摘要唯一() {
        String digest = "a".repeat(64);
        jdbc.update("insert into artifacts "
            + "(sha256, storage_key, content_type, size_bytes, file_count) "
            + "values (?, 'k/1', 'application/zip', 10, 1)", digest);

        assertThatThrownBy(() -> jdbc.update("insert into artifacts "
            + "(sha256, storage_key, content_type, size_bytes, file_count) "
            + "values (?, 'k/2', 'application/zip', 10, 1)", digest))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 版本行记着「建这一版时依据的云端摘要」。
     *
     * 基线复检要靠它：转正时再对一次 cloud 与它对不对得上。
     * 列没了或类型不对，复检就形同虚设。
     */
    @Test
    void 版本行记着基线摘要() {
        UUID userId = insertUser("u-base");
        UUID skillId = insertSkill(userId, "base-demo");
        String digest = nextDigest();
        jdbc.update("insert into skill_versions "
                + "(skill_id, version, channel, status, base_revision) "
                + "values (?, '1.0.0', 'stable', 'draft', ?)",
            skillId, digest);
        String stored = jdbc.queryForObject(
            "select base_revision from skill_versions where skill_id = ? and version = '1.0.0'",
            String.class, skillId);
        assertThat(stored).isEqualTo(digest);
    }

    /**
     * 邀请表的 org_role 只收「邀请能授」的那几档。
     *
     * owner 只能由现任 owner 直接任命，给不了——数据库要承载这条不变量，
     * 不能只靠应用层的那几道闸。
     */
    @Test
    void 邀请的组织角色不接受owner() {
        UUID orgId = insertOrganization();
        insertInvitation(orgId, "member");

        assertThatThrownBy(() -> insertInvitation(orgId, "owner"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertOrganization() {
        return jdbc.queryForObject(
            "insert into organizations (slug, name) values (?, ?) returning id",
            UUID.class, "org-" + UUID.randomUUID(), "Org");
    }

    private void insertInvitation(UUID orgId, String orgRole) {
        jdbc.update("insert into org_invitations "
            + "(organization_id, org_role, team_role, token_hash, status, expires_at) "
            + "values (?, ?, 'member', ?, 'pending', now() + interval '1 day')",
            orgId, orgRole, nextDigest());
    }

    private UUID insertUser(String subject) {
        jdbc.update("insert into users (oidc_issuer, oidc_subject, display_name) "
            + "values ('https://kc', ?, ?)", subject, "User " + subject);
        return jdbc.queryForObject(
            "select id from users where oidc_subject = ?", UUID.class, subject);
    }

    private UUID insertSkill(UUID userId, String slug) {
        jdbc.update("insert into skills (slug, name, owner_user_id) values (?, ?, ?)",
            slug, slug, userId);
        return jdbc.queryForObject(
            "select id from skills where slug = ?", UUID.class, slug);
    }

    private void insertVersion(UUID skillId, String version, String status) {
        String digest = nextDigest();
        UUID artifactId = jdbc.queryForObject(
            "insert into artifacts (sha256, storage_key, content_type, size_bytes, file_count) "
                + "values (?, ?, 'application/zip', 10, 1) returning id",
            UUID.class, digest, "k/" + digest);
        jdbc.update("insert into skill_versions "
                + "(skill_id, version, channel, status, artifact_id, published_at) "
                + "values (?, ?, 'stable', ?, ?, now())",
            skillId, version, status, artifactId);
    }

    /** 每次调用给出不同的 64 位十六进制摘要，避免先撞上 artifacts 的唯一约束。 */
    private String nextDigest() {
        artifactCounter++;
        return String.format("%064x", artifactCounter);
    }
}
