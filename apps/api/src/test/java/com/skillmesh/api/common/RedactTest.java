package com.skillmesh.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 路径脱敏。
 *
 * 关键在两处：Windows 的反斜杠也是分隔符（只按斜杠数段数会把整条路径当一段，
 * 脱敏等于没做）；段数不够两段时原样返回，压过头反而看不懂。
 */
class RedactTest {

    @Test
    void keepsLastTwoSegmentsOfWindowsPath() {
        assertThat(Redact.path("C:\\Users\\zhang\\.claude\\skills\\demo"))
            .isEqualTo("…/skills/demo");
    }

    @Test
    void keepsLastTwoSegmentsOfPosixPath() {
        assertThat(Redact.path("/home/zhang/.claude/skills/demo"))
            .isEqualTo("…/skills/demo");
    }

    @Test
    void keepsShortPathAsIs() {
        assertThat(Redact.path("/opt/demo")).isEqualTo("/opt/demo");
        assertThat(Redact.path("demo")).isEqualTo("demo");
    }

    @Test
    void hidesOnlyWhenThereIsSomethingToHide() {
        assertThat(Redact.path("/opt/skills/demo")).isEqualTo("…/skills/demo");
    }

    @Test
    void passesNullAndBlankThrough() {
        assertThat(Redact.path(null)).isNull();
        assertThat(Redact.path("  ")).isEqualTo("  ");
    }
}
