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
