package com.skillmesh.api.version.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 对应 OpenAPI 的 SkillVersion。 */
public record SkillVersionResponse(
        UUID id,
        @JsonProperty("skill_id") UUID skillId,
        String version,
        String channel,
        String status,
        @JsonProperty("source_commit") String sourceCommit,
        ArtifactSummary artifact,
        List<ManifestEntry> manifest,
        @JsonProperty("published_at") OffsetDateTime publishedAt,
        @JsonProperty("created_at") OffsetDateTime createdAt) {

    public record ArtifactSummary(
            UUID id,
            String sha256,
            @JsonProperty("size_bytes") long sizeBytes,
            @JsonProperty("content_type") String contentType) {}

    /** 逐文件清单。客户端用它算规范化摘要，判断内容是否真的变了。 */
    public record ManifestEntry(String path, String sha256, @JsonProperty("size_bytes") long sizeBytes) {}
}
