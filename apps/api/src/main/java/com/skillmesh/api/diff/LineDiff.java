package com.skillmesh.api.diff;

/**
 * 行级增删统计，口径对齐 CLI 的 lineDiff（apps/cli/internal/cmd/diff.go）。
 *
 * 用 LCS 算公共行数：added = 新行数 - 公共行数，removed = 旧行数 - 公共行数。
 */
public final class LineDiff {

    /** LCS DP 表的格数上限，超过就退化成只报文件级。 */
    public static final int MAX_CELLS = 4_000_000;

    /** 增删行数之和超过这个数就标 truncated——差异大到没人逐行看。 */
    public static final int MAX_LINES = 200;

    private LineDiff() {}

    /**
     * 返回 {added, removed}。
     *
     * LCS 表太大算不动时返回 null——跟「算出 0 增 0 删」不是一回事，
     * 调用方要能分清「没差异」和「算不动」。
     */
    public static int[] count(String oldText, String newText) {
        String[] oldLines = split(oldText);
        String[] newLines = split(newText);
        if ((long) oldLines.length * newLines.length > MAX_CELLS) {
            return null;
        }
        int common = lcsLength(oldLines, newLines);
        return new int[] {newLines.length - common, oldLines.length - common};
    }

    /** 二进制判定：前 8192 字节含 NUL 就是二进制，不做行统计。 */
    public static boolean isBinary(byte[] data) {
        int limit = Math.min(data.length, 8192);
        for (int i = 0; i < limit; i++) {
            if (data[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String[] split(String text) {
        return text.split("\n", -1);
    }

    private static int lcsLength(String[] oldLines, String[] newLines) {
        int[][] common = new int[oldLines.length + 1][newLines.length + 1];
        for (int i = oldLines.length - 1; i >= 0; i--) {
            for (int j = newLines.length - 1; j >= 0; j--) {
                if (oldLines[i].equals(newLines[j])) {
                    common[i][j] = common[i + 1][j + 1] + 1;
                } else {
                    common[i][j] = Math.max(common[i + 1][j], common[i][j + 1]);
                }
            }
        }
        return common[0][0];
    }
}
