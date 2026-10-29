package com.skillmesh.api.version;

import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import java.util.Collection;

/**
 * 新版本号：语义化版本的 patch 位加一。
 *
 * 撞号要避开。这个 Skill 已有的版本号包括草稿与待审的——
 * 它们没进 latest_version，只看 latest_version 会算出同一个号，
 * 撞 UNIQUE(skill_id, version) 就是 500。
 */
public final class VersionNumberGenerator {

    private VersionNumberGenerator() {}

    /**
     * 在 latest 上加一，跳过 used 里已有的号。latest 为空表示还没有版本。
     *
     * used 要传全这个 Skill 已有的版本号（含草稿与待审），不只是 latest_version。
     */
    public static String next(String latest, Collection<String> used) {
        String candidate = bump(latest);
        while (used.contains(candidate)) {
            candidate = bump(candidate);
        }
        return candidate;
    }

    /** patch 位加一。解析不了就报错——别猜。 */
    private static String bump(String version) {
        if (version == null || version.isBlank()) {
            return "1.0.0";
        }
        String[] parts = version.split("\\.");
        if (parts.length != 3) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "版本号 " + version + " 不是 x.y.z 形式，请自己指定新版本号");
        }
        try {
            int patch = Integer.parseInt(parts[2]);
            return parts[0] + "." + parts[1] + "." + (patch + 1);
        } catch (NumberFormatException e) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "版本号 " + version + " 的 patch 位不是数字，请自己指定新版本号");
        }
    }
}
