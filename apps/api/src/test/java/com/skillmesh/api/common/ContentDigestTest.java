package com.skillmesh.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 内容摘要要和 CLI 逐字节一致（详细设计 §6.2）。
 *
 * 摘要算在「路径 + 文件摘要」排序之后——排序规则两边必须一样，
 * 不一样的话同一份内容两边的摘要不同，基线校验会一直失败。
 *
 * 排序按**码点**（不是 UTF-16 码元）：CLI 那边是 Go 的字符串比较，
 * 那是 UTF-8 字节序，等于码点序。增补平面字符（emoji 之类）
 * 在 UTF-16 里是代理对，码元序会把它排到 U+E000..U+FFFF 之前——
 * 两边排序不同，摘要就永远对不上。
 */
class ContentDigestTest {

    @Test
    void 普通路径的摘要与CLI一致() {
        List<Map<String, Object>> manifest = List.of(
            Map.of("path", "a.md", "sha256", "cc"),
            Map.of("path", "b.md", "sha256", "dd"));
        // 这是 CLI skillscan.Digest 对同样两个文件算出来的值。
        assertThat(ContentDigest.of(manifest))
            .isEqualTo("9c9701ab56a3f6edd25b20530f9d9d89272d634a3b59201c375c9273524b92f7");
    }

    @Test
    void 含增补平面字符的路径按码点排序() {
        List<Map<String, Object>> manifest = List.of(
            Map.of("path", "😀.md", "sha256", "aa"),
            Map.of("path", "�.md", "sha256", "bb"),
            Map.of("path", "a.md", "sha256", "cc"));
        // CLI skillscan.Digest 对同样三个文件算出来的值（Go 的字符串比较 = 码点序）。
        assertThat(ContentDigest.of(manifest))
            .isEqualTo("aae24b34af6740df839ceeb3417c6b681ffb76450022ac72bf4c714e0ca3bb81");
    }
}
