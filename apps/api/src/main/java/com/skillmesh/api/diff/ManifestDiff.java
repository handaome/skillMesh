package com.skillmesh.api.diff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 比两个 manifest，找新增/修改/删除的文件。
 *
 * manifest 是逐文件的 path + sha256（skill_versions.manifest_json 存着）。
 * 比摘要就够——内容相同则文件相同。
 */
public final class ManifestDiff {

    private ManifestDiff() {}

    /**
     * 清单级比对：只分出新增/修改/删除，不算行数。
     *
     * modified 的行数是**占位**（0, 0, truncated=true）——这里没有制品，
     * 算不出行数。调用方要真行数的话必须自己补：
     * {@link ArtifactDiffService} 会解包重算一遍。
     */
    public static List<FileChange> compare(List<Map<String, Object>> base,
                                            List<Map<String, Object>> next) {
        Map<String, String> baseIndex = index(base);
        Map<String, String> nextIndex = index(next);
        List<FileChange> changes = new ArrayList<>();

        for (Map.Entry<String, String> entry : nextIndex.entrySet()) {
            String previous = baseIndex.get(entry.getKey());
            if (previous == null) {
                changes.add(FileChange.added(entry.getKey()));
            } else if (!previous.equals(entry.getValue())) {
                changes.add(FileChange.modified(entry.getKey(), 0, 0, true));
            }
        }
        for (String path : baseIndex.keySet()) {
            if (!nextIndex.containsKey(path)) {
                changes.add(FileChange.removed(path));
            }
        }
        changes.sort((left, right) -> left.path().compareTo(right.path()));
        return changes;
    }

    /**
     * 清单按路径索引。
     *
     * 重名路径取**最后一个**（{@code put} 覆盖前一个）：与
     * {@code ArtifactDiffService.readEntry}、CLI 安装器（后一个 entry 覆盖前一个）
     * 同一个口径——面板显示的必须是客户端真会装的那一份。
     * 重名包现在在上传时就被拒（{@code ZipManifest}），这里只是兜底。
     */
    private static Map<String, String> index(List<Map<String, Object>> manifest) {
        Map<String, String> result = new LinkedHashMap<>();
        if (manifest == null) {
            return result;
        }
        for (Map<String, Object> entry : manifest) {
            Object path = entry.get("path");
            Object sha = entry.get("sha256");
            if (path != null && sha != null) {
                result.put(path.toString(), sha.toString());
            }
        }
        return result;
    }
}
