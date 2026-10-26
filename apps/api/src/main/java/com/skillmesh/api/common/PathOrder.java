package com.skillmesh.api.common;

import java.util.Comparator;

/**
 * 路径的排序口径：按 Unicode 码点比。
 *
 * 不能用 {@code String.compareTo}：那是 UTF-16 码元序，增补平面字符
 * （emoji 之类）的代理对落在 U+E000..U+FFFF 之前，而 CLI 那边（Go 的字符串
 * 比较）是 UTF-8 字节序，等于码点序。两边排序不同，同一份内容的摘要就对
 * 不上——基线校验会一直失败。
 *
 * 排清单的每处都得用这个：服务端排一遍算摘要，客户端再排一遍核摘要，
 * 口径必须一模一样。
 */
public final class PathOrder {

    private PathOrder() {}

    /** 路径比较器：按码点比。 */
    public static final Comparator<String> BY_CODE_POINT = PathOrder::compareByCodePoint;

    private static int compareByCodePoint(String left, String right) {
        int[] a = left.codePoints().toArray();
        int[] b = right.codePoints().toArray();
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return Integer.compare(a.length, b.length);
    }
}
