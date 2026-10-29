package com.skillmesh.api.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmesh.api.common.ApiException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 版本号要能加一，还要避开已经占掉的号。
 *
 * 撞号不是小问题：待审与草稿的版本号没进 latest_version，
 * 只看 latest_version 会算出同一个号，撞唯一约束就是 500。
 */
class VersionNumberGeneratorTest {

    @Test
    void bumpsPatch() {
        assertThat(VersionNumberGenerator.next("1.2.3", Set.of())).isEqualTo("1.2.4");
    }

    @Test
    void startsAtOneWhenNoVersionYet() {
        assertThat(VersionNumberGenerator.next(null, Set.of())).isEqualTo("1.0.0");
    }

    @Test
    void skipsTakenVersions() {
        // 1.0.1 是个待审版本：latest_version 还是 1.0.0，但它已经占了这个号。
        assertThat(VersionNumberGenerator.next("1.0.0", List.of("1.0.0", "1.0.1", "1.0.2")))
            .isEqualTo("1.0.3");
    }

    @Test
    void refusesToGuessNonSemver() {
        assertThatThrownBy(() -> VersionNumberGenerator.next("2024-01-01", Set.of()))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("x.y.z");
    }
}
