package com.skillmesh.api.diff;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** 两个版本的差异。baseVersion 为空表示没有基线（首个版本，全部算新增）。 */
public record ArtifactDiffResult(
        @JsonProperty("base_version") String baseVersion,
        boolean truncated,
        List<FileChange> files) {

    public static ArtifactDiffResult empty() {
        return new ArtifactDiffResult("", false, List.of());
    }
}
