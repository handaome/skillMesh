package com.skillmesh.api.sync.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 变更集结果。status=conflict 时 conflicts 里有具体冲突点。 */
public record SyncChangesetResponse(
        @JsonProperty("changeset_id") UUID changesetId,
        @JsonProperty("skill_id") UUID skillId,
        String status,
        @JsonProperty("cloud_hash") String cloudHash,
        String message,
        List<Conflict> conflicts,
        @JsonProperty("created_at") OffsetDateTime createdAt) {

    public record Conflict(String path, String base, String local, String cloud, String type) {}
}
