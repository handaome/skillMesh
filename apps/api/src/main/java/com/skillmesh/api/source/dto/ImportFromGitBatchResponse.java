package com.skillmesh.api.source.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 批量导入的结果。逐条独立：成的与败的分两组报出来。
 */
public record ImportFromGitBatchResponse(
        List<Imported> imported,
        List<Failed> failed) {

    /** 导入成功的一条。 */
    public record Imported(
            @JsonProperty("path") String path,
            @JsonProperty("skill_id") String skillId,
            String slug,
            String version,
            @JsonProperty("source_commit") String sourceCommit,
            String status) {
    }

    /** 导入失败的一条，连原因一起报。 */
    public record Failed(String path, String reason) {
    }
}
