package com.skillmesh.api.version.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
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
        @JsonProperty("published_at") OffsetDateTime publishedAt,
        @JsonProperty("created_at") OffsetDateTime createdAt) {

    public record ArtifactSummary(
            UUID id,
            String sha256,
            @JsonProperty("size_bytes") long sizeBytes,
            @JsonProperty("content_type") String contentType) {}
}
