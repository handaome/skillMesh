package com.skillmesh.api.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 对应 OpenAPI 的 Skill schema。 */
public record SkillResponse(
        UUID id,
        String slug,
        String name,
        String description,
        String visibility,
        String status,
        @JsonProperty("owner_user_id") UUID ownerUserId,
        @JsonProperty("organization_id") UUID organizationId,
        @JsonProperty("owner_team_id") UUID ownerTeamId,
        List<String> tags,
        Map<String, Object> metadata,
        @JsonProperty("latest_version") String latestVersion,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt) {}
