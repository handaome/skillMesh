package com.skillmesh.api.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** 对应 OpenAPI 的 SkillPage。 */
public record SkillPage(
        List<SkillResponse> items,
        @JsonProperty("next_cursor") String nextCursor,
        @JsonProperty("has_more") boolean hasMore) {}
