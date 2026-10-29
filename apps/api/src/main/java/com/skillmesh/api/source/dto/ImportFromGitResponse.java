package com.skillmesh.api.source.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 从 Git 导入的结果。 */
public record ImportFromGitResponse(
        @JsonProperty("skill_id") String skillId,
        String slug,
        String version,
        @JsonProperty("source_commit") String sourceCommit,
        String sha256,
        @JsonProperty("file_count") int fileCount,
        /** 版本的最终状态：published / pending_review / quarantined。 */
        String status) {}
