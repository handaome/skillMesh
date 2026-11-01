package com.skillmesh.api.version;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmesh.api.org.Role;
import org.junit.jupiter.api.Test;

/**
 * 谁能撤回版本（详细设计 §2.4 权限矩阵「撤回版本」一行）。
 *
 * 撤回不可逆，所以边界要窄：属主，或组织里的 owner/admin；再往下一律不行。
 */
class SkillVersionRevokeRightTest {

    @Test
    void 属主能撤回() {
        assertThat(SkillVersionService.canRevoke(true, null)).isTrue();
        assertThat(SkillVersionService.canRevoke(true, Role.member)).isTrue();
    }

    @Test
    void 组织管理员能撤回() {
        assertThat(SkillVersionService.canRevoke(false, Role.owner)).isTrue();
        assertThat(SkillVersionService.canRevoke(false, Role.admin)).isTrue();
    }

    @Test
    void 其余人不能撤回() {
        assertThat(SkillVersionService.canRevoke(false, null)).isFalse();
        assertThat(SkillVersionService.canRevoke(false, Role.publisher)).isFalse();
        assertThat(SkillVersionService.canRevoke(false, Role.reviewer)).isFalse();
        assertThat(SkillVersionService.canRevoke(false, Role.member)).isFalse();
        assertThat(SkillVersionService.canRevoke(false, Role.viewer)).isFalse();
    }
}
