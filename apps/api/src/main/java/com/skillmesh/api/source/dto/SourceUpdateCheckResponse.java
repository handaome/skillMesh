package com.skillmesh.api.source.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 检查 Git 来源有没有新提交的结果。 */
public record SourceUpdateCheckResponse(
        @JsonProperty("repository_url") String repositoryUrl,
        @JsonProperty("ref_value") String refValue,
        @JsonProperty("current_commit") String currentCommit,
        @JsonProperty("remote_commit") String remoteCommit,
        @JsonProperty("has_update") boolean hasUpdate) {}
