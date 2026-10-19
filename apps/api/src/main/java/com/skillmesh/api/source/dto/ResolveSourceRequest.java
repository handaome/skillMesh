package com.skillmesh.api.source.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 对应 OpenAPI 的 ResolveSourceRequest。 */
public record ResolveSourceRequest(
        @NotBlank @Size(max = 512) @JsonProperty("repository_url") String repositoryUrl,
        @Size(max = 512) String path,
        @Size(max = 200) String ref) {}
