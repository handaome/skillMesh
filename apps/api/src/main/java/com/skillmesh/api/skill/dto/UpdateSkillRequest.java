package com.skillmesh.api.skill.dto;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/** PATCH /skills/{id}：只改展示性元数据，不动 slug 与可见性。 */
public record UpdateSkillRequest(
        @Size(max = 120) String name,
        @Size(max = 4000) String description,
        @Size(max = 30) List<@Size(max = 64) String> tags,
        Map<String, Object> metadata) {}
