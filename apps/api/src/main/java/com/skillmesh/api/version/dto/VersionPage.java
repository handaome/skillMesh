package com.skillmesh.api.version.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** 版本列表。v1 数据量小，一次返回全部。 */
public record VersionPage(
        List<SkillVersionResponse> items,
        @JsonProperty("next_cursor") String nextCursor,
        @JsonProperty("has_more") boolean hasMore) {}
