package com.skillmesh.api.skill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 对应 OpenAPI 的 CreateSkillRequest。字段名用 snake_case 保持与契约一致。 */
public record CreateSkillRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,63}$") String slug,
        @NotBlank @Size(max = 120) String name,
        @NotNull @Size(max = 4000) String description,
        @NotNull String visibility,
        UUID organization_id,
        UUID owner_team_id,
        @Size(max = 30) List<@Size(max = 64) String> tags,
        Map<String, Object> metadata) {}
