package com.skillmesh.api.diff;

/**
 * 一个文件在两个版本之间的变化。
 *
 * kind：added / modified / removed。added/removed 只对 modified 有意义。
 * truncated 表示差异大到只报文件级（行数不可信或算不动）。
 */
public record FileChange(String path, String kind, int added, int removed, boolean truncated) {

    public static FileChange added(String path) {
        return new FileChange(path, "added", 0, 0, false);
    }

    public static FileChange removed(String path) {
        return new FileChange(path, "removed", 0, 0, false);
    }

    public static FileChange modified(String path, int added, int removed, boolean truncated) {
        return new FileChange(path, "modified", added, removed, truncated);
    }
}
