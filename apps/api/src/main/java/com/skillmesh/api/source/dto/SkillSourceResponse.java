package com.skillmesh.api.source.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 一个 Skill 的 Git 来源。 */
public record SkillSourceResponse(
        @JsonProperty("source_type") String sourceType,
        String provider,
        @JsonProperty("repository_url") String repositoryUrl,
        String subpath,
        @JsonProperty("ref_type") String refType,
        @JsonProperty("ref_value") String refValue,
        @JsonProperty("resolved_commit") String resolvedCommit) {}
