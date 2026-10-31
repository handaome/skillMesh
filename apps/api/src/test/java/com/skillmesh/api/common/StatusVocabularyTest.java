package com.skillmesh.api.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmesh.api.audit.AuditOutcome;
import com.skillmesh.api.auth.UserStatus;
import com.skillmesh.api.group.GroupStatus;
import com.skillmesh.api.install.InstallationStatus;
import com.skillmesh.api.invite.InvitationStatus;
import com.skillmesh.api.org.OrganizationStatus;
import com.skillmesh.api.org.TeamStatus;
import com.skillmesh.api.review.ReviewDecision;
import com.skillmesh.api.scan.ScanStatus;
import com.skillmesh.api.skill.LicenseStatus;
import com.skillmesh.api.skill.SkillStatus;
import com.skillmesh.api.sync.ChangesetStatus;
import com.skillmesh.api.sync.ConflictStatus;
import com.skillmesh.api.sync.SyncStateStatus;
import com.skillmesh.api.upload.UploadStatus;
import com.skillmesh.api.version.VersionStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 状态枚举与数据库的取值必须一一对上。
 *
 * 枚举常量名就是数据库里存的值（可见性除外，它多一层映射）。
 * 两边一旦对不上，写进去就撞 CHECK 约束——而且是运行期才发现，
 * 还是那种「约束名看不懂」的报错。
 *
 * 所以这里直接读迁移文件里的 CHECK 约束，跟枚举比：
 * 加了枚举常量忘了改约束，在这里就红。
 */
class StatusVocabularyTest {

    /** 一列状态与它对应的枚举。 */
    private record Bound(String table, String column, Class<?> type) {}

    private static final List<Bound> BOUND = List.of(
        new Bound("users", "status", UserStatus.class),
        new Bound("organizations", "status", OrganizationStatus.class),
        new Bound("teams", "status", TeamStatus.class),
        new Bound("org_invitations", "status", InvitationStatus.class),
        new Bound("skills", "status", SkillStatus.class),
        new Bound("skills", "visibility", Visibility.class),
        new Bound("skill_sources", "license_status", LicenseStatus.class),
        new Bound("skill_versions", "status", VersionStatus.class),
        new Bound("skill_scans", "status", ScanStatus.class),
        new Bound("skill_reviews", "decision", ReviewDecision.class),
        new Bound("skill_groups", "status", GroupStatus.class),
        new Bound("skill_groups", "visibility", Visibility.class),
        new Bound("installations", "status", InstallationStatus.class),
        new Bound("sync_states", "status", SyncStateStatus.class),
        new Bound("sync_changesets", "status", ChangesetStatus.class),
        new Bound("sync_conflicts", "status", ConflictStatus.class),
        new Bound("upload_sessions", "status", UploadStatus.class),
        new Bound("audit_events", "outcome", AuditOutcome.class));

    @Test
    void 枚举的取值与数据库的约束一致() {
        Map<String, Set<String>> allowed = allowedValues();
        for (Bound bound : BOUND) {
            Set<String> expected = allowed.get(bound.table() + "." + bound.column());
            assertThat(expected)
                .as("迁移文件里找不到 %s.%s 的 CHECK 约束", bound.table(), bound.column())
                .isNotNull();
            assertThat(values(bound.type()))
                .as("%s.%s 的枚举取值与数据库约束对不上", bound.table(), bound.column())
                .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    @Test
    void 认不出来的取值报400而不是放行() {
        assertThat(EnumParams.require(VersionStatus.class, "published", "status"))
            .isEqualTo(VersionStatus.published);
        assertThat(EnumParams.parse(VersionStatus.class, "published")).isNotNull();
        assertThat(EnumParams.parse(VersionStatus.class, "publishd")).isNull();
        assertThatThrownBy(() -> EnumParams.require(VersionStatus.class, "publishd", "status"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("status");
    }

    /** 大小写与首尾空格：查询参数与请求体两边口径一致，同一个值换个入口不会一个认一个不认。 */
    @Test
    void 取值不区分大小写也不认首尾空格() {
        assertThat(EnumParams.parse(VersionStatus.class, " PUBLISHED "))
            .isEqualTo(VersionStatus.published);
        assertThat(Visibility.of(" private ")).isEqualTo(Visibility.PRIVATE);
    }

    /** 枚举在数据库里存的值。 */
    private static Set<String> values(Class<?> type) {
        List<String> out = new ArrayList<>();
        for (Object constant : type.getEnumConstants()) {
            out.add(constant instanceof Visibility visibility ? visibility.value() : constant.toString());
        }
        return Set.copyOf(out);
    }

    /**
     * 从迁移文件里扒出所有 {@code CHECK (列 IN (...))} 的取值。
     *
     * 只认 IN 那种写法：蕴含式的约束（{@code CHECK (status <> 'published' OR ...)}）
     * 不是词表，里面那几串字是别的意思。
     */
    private static Map<String, Set<String>> allowedValues() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Pattern table = Pattern.compile("CREATE TABLE (\\w+) \\((.*?)\\n\\);", Pattern.DOTALL);
        Pattern check = Pattern.compile("CHECK \\((\\w+) IN \\(([^)]*)\\)\\)");
        Pattern literal = Pattern.compile("'([^']*)'");
        for (Path file : migrationFiles()) {
            String sql = read(file);
            Matcher tables = table.matcher(sql);
            while (tables.find()) {
                Matcher checks = check.matcher(tables.group(2));
                while (checks.find()) {
                    Set<String> values = new java.util.LinkedHashSet<>();
                    Matcher literals = literal.matcher(checks.group(2));
                    while (literals.find()) {
                        values.add(literals.group(1));
                    }
                    out.put(tables.group(1) + "." + checks.group(1), Set.copyOf(values));
                }
            }
        }
        return out;
    }

    /** 迁移文件：打包后它们在 classpath 的 db/migrations 下。 */
    private static List<Path> migrationFiles() {
        try {
            Path dir = Path.of(Objects.requireNonNull(
                StatusVocabularyTest.class.getResource("/db/migrations"), "找不到迁移目录").toURI());
            try (Stream<Path> files = Files.list(dir)) {
                return files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
