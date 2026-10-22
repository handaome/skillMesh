package com.skillmesh.api.version.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 编辑的结果：新版本的信息与它的状态。
 *
 * {@code status} 是版本自己的状态（published / pending_review / rejected）——
 * 不是「上传完了没有」。团队与组织可见的版本会停在待审核。
 */
public record EditFileResponse(
        @JsonProperty("skill_id") java.util.UUID skillId,
        String version,
        @JsonProperty("file_count") int fileCount,
        String sha256,
        String status) {}
