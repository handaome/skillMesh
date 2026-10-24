package com.skillmesh.api.version;

import com.skillmesh.api.common.ContentDigest;
import java.util.List;

/**
 * 云端当前已发布版本的内容摘要。
 *
 * 同一份算法散在三个地方（同步提交、建版本、版本转正），各自抄一遍迟早抄歪：
 * 客户端拿它当基线，服务端拿它当判据，两边口径差一点，基线就永远对不上。
 * 收在一处，谁都用这份。
 */
public final class PublishedDigest {

    private PublishedDigest() {}

    /**
     * 最新已发布版本的内容摘要。没有已发布版本时返回 null。
     *
     * 口径：
     * - 只看已发布版本（草稿、待审、撤回的都不算）；
     * - 已发布但没有制品的跳过（撤回不删版本行，但那种行不再代表可用内容）；
     * - 清单为空的跳过，继续往下找（更早的版本可能才有清单）。
     */
    public static String of(List<SkillVersionEntity> rows) {
        for (SkillVersionEntity version : rows) {
            if (!"published".equals(version.getStatus())) {
                continue;
            }
            if (version.getArtifactId() == null) {
                continue;
            }
            if (version.getManifestJson() == null || version.getManifestJson().isEmpty()) {
                continue;
            }
            return ContentDigest.of(version.getManifestJson());
        }
        return null;
    }
}
