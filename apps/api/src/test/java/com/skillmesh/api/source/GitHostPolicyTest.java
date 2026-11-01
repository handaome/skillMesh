package com.skillmesh.api.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Git 主机闸门（设计文档 §7.3、§14 的 SSRF 防护）。
 *
 * 纯单元测试：闸门本身不碰网络（只解析地址），自己 new 出来即可，
 * 不必拉起整个 Spring 上下文。
 */
class GitHostPolicyTest {

    private final GitHostPolicy policy = new GitHostPolicy("github.com,gitlab.com");

    @Test
    void 只放行白名单里的https与ssh地址() {
        assertThat(policy.requireAllowed("https://github.com/owner/repo.git"))
            .isEqualTo("https://github.com/owner/repo.git");
        assertThat(policy.requireAllowed("ssh://git@github.com/owner/repo.git")).contains("github");
    }

    @Test
    void 危险协议与带密码的地址一律拒() {
        assertThatThrownBy(() -> policy.requireAllowed("file:///etc/passwd"))
            .hasMessageContaining("只允许");
        assertThatThrownBy(() -> policy.requireAllowed("http://github.com/owner/repo.git"))
            .hasMessageContaining("只允许");
        assertThatThrownBy(() -> policy.requireAllowed("https://user:pass@github.com/x.git"))
            .hasMessageContaining("密码");
    }

    @Test
    void 不在白名单里的主机要被挡下来() {
        assertThatThrownBy(() -> policy.requireAllowed("https://evil.example.com/x.git"))
            .hasMessageContaining("允许列表");
        assertThatThrownBy(() -> policy.requireAllowed("https://127.0.0.1/x.git"))
            .hasMessageContaining("允许列表");
        assertThatThrownBy(() -> policy.requireAllowed("https://localhost/x.git"))
            .hasMessageContaining("允许列表");
    }
}
