package com.skillmesh.api.version;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 版本号大小比较。
 *
 * latest_version 靠它决定往新走还是往旧走：比较规则写错了，
 * 「先过审 1.9.0 再过审 1.10.0」就会把 latest 留在 1.9.0。
 */
class VersionOrderTest {

    @Test
    void 点分字段按数字比而不是字典序() {
        // 字典序下 "10" < "9"，按数字才是 1.10.0 更大
        assertThat(VersionOrder.isGreater("1.10.0", "1.9.0")).isTrue();
        assertThat(VersionOrder.isGreater("1.9.0", "1.10.0")).isFalse();
        assertThat(VersionOrder.isGreater("2.0.0", "1.99.99")).isTrue();
        assertThat(VersionOrder.isGreater("1.0.10", "1.0.9")).isTrue();
    }

    @Test
    void 缺的字段当零() {
        assertThat(VersionOrder.isGreater("1.0", "1.0.0")).isFalse();
        assertThat(VersionOrder.isGreater("1.0.1", "1.0")).isTrue();
        assertThat(VersionOrder.isGreater("1.1", "1.0.9")).isTrue();
    }

    @Test
    void 当前为空时一律算新的() {
        assertThat(VersionOrder.isGreater("1.0.0", null)).isTrue();
        assertThat(VersionOrder.isGreater("1.0.0", "")).isTrue();
        assertThat(VersionOrder.isGreater("1.0.0", "   ")).isTrue();
    }

    @Test
    void 候选为空不算新的() {
        assertThat(VersionOrder.isGreater(null, "1.0.0")).isFalse();
        assertThat(VersionOrder.isGreater("", "1.0.0")).isFalse();
    }

    @Test
    void 字段不是数字就按字典序比() {
        // 版本号写歪了也别把 latest 弄丢：至少要能分出大小
        assertThat(VersionOrder.isGreater("1.0.beta", "1.0.alpha")).isTrue();
        assertThat(VersionOrder.isGreater("1.0.alpha", "1.0.beta")).isFalse();
    }

    @Test
    void 相同版本号不算更大() {
        assertThat(VersionOrder.isGreater("1.2.3", "1.2.3")).isFalse();
    }
}
