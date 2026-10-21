package com.skillmesh.api.version;

/**
 * 版本号大小比较。
 *
 * latest_version 只能往新版本走：先过审 v1.1.0 再过审 v1.0.1，
 * latest 不能被旧版本顶回去（展示层说的「最新版本」从此是旧的一版）。
 */
public final class VersionOrder {

    private VersionOrder() {}

    /**
     * candidate 比 current 新吗？
     *
     * 点分字段按数字比（1.10.0 &gt; 1.9.0），缺的字段当 0（1.0 与 1.0.0 一样大）。
     * 字段不是数字就按字典序比——版本号写歪了也别把 latest 弄丢。
     * current 为空（还没发过版）时一律算新的。
     */
    public static boolean isGreater(String candidate, String current) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (current == null || current.isBlank()) {
            return true;
        }
        String[] left = candidate.split("\\.");
        String[] right = current.split("\\.");
        int fields = Math.max(left.length, right.length);
        for (int i = 0; i < fields; i++) {
            int cmp = compareSegment(i < left.length ? left[i] : "0",
                i < right.length ? right[i] : "0");
            if (cmp != 0) {
                return cmp > 0;
            }
        }
        return false;
    }

    private static int compareSegment(String left, String right) {
        Long a = number(left);
        Long b = number(right);
        if (a != null && b != null) {
            return Long.compare(a, b);
        }
        return left.compareTo(right);
    }

    private static Long number(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
