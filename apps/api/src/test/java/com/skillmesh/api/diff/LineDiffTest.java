package com.skillmesh.api.diff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LineDiffTest {

    @Test
    void 算出增删行数() {
        int[] counts = LineDiff.count("a\nb\nc\n", "a\nx\nc\nd\n");
        assertThat(counts[0]).isEqualTo(2);  // added
        assertThat(counts[1]).isEqualTo(1);  // removed
    }

    @Test
    void 完全相同没有增删() {
        int[] counts = LineDiff.count("a\nb\n", "a\nb\n");
        assertThat(counts[0]).isZero();
        assertThat(counts[1]).isZero();
    }

    @Test
    void 二进制内容不参与行统计() {
        byte[] binary = new byte[] {'a', 0, 'b'};
        assertThat(LineDiff.isBinary(binary)).isTrue();
        assertThat(LineDiff.isBinary("纯文本".getBytes())).isFalse();
    }

    @Test
    void 行数乘积超上限就不算行数() {
        String oldText = "a\n".repeat(2000);
        String newText = "b\n".repeat(2001);
        assertThat(LineDiff.count(oldText, newText)).isNull();
    }
}
