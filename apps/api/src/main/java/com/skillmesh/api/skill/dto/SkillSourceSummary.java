package com.skillmesh.api.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Skill 的来源摘要。
 *
 * 列表页与市场页都要显示「这个 Skill 是从哪个仓库来的」——
 * 摊在列表里比让人点进详情才知道强。内容与来源表一致：
 * 仓库、子目录、ref 与实际落地的 commit。
 */
public record SkillSourceSummary(
        @JsonProperty("source_type") String sourceType,
        String provider,
        @JsonProperty("repository_url") String repositoryUrl,
        String subpath,
        @JsonProperty("ref_type") String refType,
        @JsonProperty("ref_value") String refValue,
        @JsonProperty("resolved_commit") String resolvedCommit) {}
