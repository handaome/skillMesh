package com.skillmesh.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 状态在线上的样子：全小写。
 *
 * Go CLI 与前端都拿这些字面量跟服务端对：
 * {@code version.Status != "published"}、{@code VISIBILITY_TEXT['private']}。
 * 枚举化把 String 换成了枚举，线上格式跟着变的话，编译期发现不了——
 * CLI 只是「哪个版本都没匹配上」，前端只是「状态显示不出来」。
 *
 * 所以这里把每个枚举序列化出来的字符串钉死。
 */
class StatusWireFormatTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 所有状态序列化出来都是小写() throws Exception {
        Map<String, List<?>> enums = Map.ofEntries(
            Map.entry("SkillStatus", List.of(SkillStatus.values())),
            Map.entry("VersionStatus", List.of(VersionStatus.values())),
            Map.entry("ScanStatus", List.of(ScanStatus.values())),
            Map.entry("ReviewDecision", List.of(ReviewDecision.values())),
            Map.entry("InstallationStatus", List.of(InstallationStatus.values())),
            Map.entry("UserStatus", List.of(UserStatus.values())),
            Map.entry("AuditOutcome", List.of(AuditOutcome.values())),
            Map.entry("OrganizationStatus", List.of(OrganizationStatus.values())),
            Map.entry("TeamStatus", List.of(TeamStatus.values())),
            Map.entry("GroupStatus", List.of(GroupStatus.values())),
            Map.entry("InvitationStatus", List.of(InvitationStatus.values())),
            Map.entry("LicenseStatus", List.of(LicenseStatus.values())),
            Map.entry("UploadStatus", List.of(UploadStatus.values())),
            Map.entry("SyncStateStatus", List.of(SyncStateStatus.values())),
            Map.entry("ChangesetStatus", List.of(ChangesetStatus.values())),
            Map.entry("ConflictStatus", List.of(ConflictStatus.values())));

        for (var entry : enums.entrySet()) {
            for (Object constant : entry.getValue()) {
                String body = json.writeValueAsString(constant);
                assertThat(body)
                    .as("%s 的 %s 序列化后应当是小写", entry.getKey(), constant)
                    .isEqualTo("\"" + ((Enum<?>) constant).name().toLowerCase(java.util.Locale.ROOT) + "\"");
            }
        }
    }

    @Test
    void 可见性序列化出来是数据库里那四个小写值() throws Exception {
        // 可见性的常量名是大写（private/public 是 Java 关键字），线上仍是小写。
        assertThat(json.writeValueAsString(Visibility.PRIVATE)).isEqualTo("\"private\"");
        assertThat(json.writeValueAsString(Visibility.TEAM)).isEqualTo("\"team\"");
        assertThat(json.writeValueAsString(Visibility.ORGANIZATION)).isEqualTo("\"organization\"");
        assertThat(json.writeValueAsString(Visibility.PUBLIC)).isEqualTo("\"public\"");
    }

    @Test
    void 认不出来的取值被挡在门外() {
        // 客户端传了没人认识的值：反序列化就该失败，而不是悄悄变成 null。
        for (Class<?> type : List.of(SkillStatus.class, VersionStatus.class, UserStatus.class,
                AuditOutcome.class, InstallationStatus.class)) {
            try {
                json.readValue("\"nope\"", type);
                throw new AssertionError(type.getSimpleName() + " 收了没人认识的取值");
            } catch (Exception e) {
                assertThat(e).isNotNull();
            }
        }
    }
}
