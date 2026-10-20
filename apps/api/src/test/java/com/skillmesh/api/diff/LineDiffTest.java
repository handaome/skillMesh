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

    /**
     * CLI 的 LCS 比原始字节，所以解码必须一一对应。
     *
     * 用 UTF-8 解码的话 0x80 与 0x81 都会变成 U+FFFD——两份不同的文件
     * 会算出「零差异」。这是 CLI 对齐的第一条不变量。
     */
    @Test
    void 非法字节不互相折叠() {
        assertThat(LineDiff.decode(new byte[] {(byte) 0x80}))
            .isNotEqualTo(LineDiff.decode(new byte[] {(byte) 0x81}));
        assertThat(LineDiff.decode(new byte[] {(byte) 0xff, (byte) 0xfe}))
            .isNotEqualTo(LineDiff.decode(new byte[] {(byte) 0xff, (byte) 0xfd}));
    }

    /**
     * 恰好 4,000,000 格（2000 × 2000）不算超限——CLI 会去算。
     *
     * 判据是 {@code >} 不是 {@code >=}：差一格就把「能算」变成「算不动」。
     * 前 1999 行相同、最后一行不同 → LCS = 1999 → 增删各 1 行。
     */
    @Test
    void 恰好四百万格还能算() {
        String oldText = lines(2000, "old");
        String newText = oldText.substring(0, oldText.lastIndexOf('\n')) + "\ndifferent";

        int[] counts = LineDiff.count(oldText, newText);

        assertThat(counts).isNotNull();
        assertThat(counts[0]).isEqualTo(1);  // added
        assertThat(counts[1]).isEqualTo(1);  // removed
    }

    /** 越过 4,000,000 格就退化成只报文件级。 */
    @Test
    void 超过四百万格就算不动() {
        String oldText = lines(2000, "old");
        String newText = lines(2001, "new");

        assertThat(LineDiff.count(oldText, newText)).isNull();
    }

    /**
     * n 行：每行内容互不相同。
     *
     * 行间用 n-1 个换行符——{@code split("\n", -1)} 会多留一个尾部空串，
     * 所以末尾不能带换行，否则切出来是 n+1 行。
     */
    private static String lines(int count, String tag) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(tag).append('-').append(i);
        }
        return sb.toString();
    }
}
