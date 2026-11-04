package com.skillmesh.api.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 冲突判定要与 CLI 的三方比较同口径（apps/cli/internal/syncstate/decision.go）。
 *
 * 只有「两边都改过、且改出来的东西不一样」才是真冲突。
 * 云端领先而本地没动过时，该做的动作是拉取——判成冲突会让同步卡住，
 * 用户明明什么都没改，却被要求 --resolve 处理。
 */
class SyncServiceTest {

    private static final String BASE = "a".repeat(64);
    private static final String LOCAL = "b".repeat(64);
    private static final String CLOUD = "c".repeat(64);

    @Test
    void cloudAheadWhileLocalUntouchedIsNotConflict() {
        // 本地还停在基线上，只是云端被别的设备更新了：拉下来就行。
        assertThat(SyncService.isConflict(BASE, BASE, CLOUD)).isFalse();
    }

    @Test
    void localAheadWhileCloudUntouchedIsNotConflict() {
        // 云端还在基线上，只有本地改了：正常上传。
        assertThat(SyncService.isConflict(BASE, LOCAL, BASE)).isFalse();
    }

    @Test
    void bothSidesChangedDifferentlyIsConflict() {
        assertThat(SyncService.isConflict(BASE, LOCAL, CLOUD)).isTrue();
    }

    @Test
    void sameContentOnBothSidesIsNotConflict() {
        assertThat(SyncService.isConflict(BASE, LOCAL, LOCAL)).isFalse();
    }

    @Test
    void emptyCloudIsNotConflict() {
        // 云端还没有内容，没什么可冲突的。
        assertThat(SyncService.isConflict(BASE, LOCAL, "")).isFalse();
        assertThat(SyncService.isConflict(BASE, LOCAL, null)).isFalse();
    }

    @Test
    void withoutBaselineAnyDifferenceIsConflict() {
        // 第一次同步，没有共同祖先：分不清谁先谁后，不盲目覆盖云端。
        assertThat(SyncService.isConflict("", LOCAL, CLOUD)).isTrue();
        assertThat(SyncService.isConflict(null, LOCAL, CLOUD)).isTrue();
    }
}
