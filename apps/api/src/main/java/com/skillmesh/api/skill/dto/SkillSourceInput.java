package com.skillmesh.api.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建 Skill 时附带的来源信息（设计文档 §7.1）。
 *
 * 客户端知道得比服务端多：这个目录当初是从哪个仓库、哪个 commit 装来的，
 * 只有它那儿有记录。服务端只负责存。
 */
public record SkillSourceInput(
        @Pattern(regexp = "local_import|git|registry|archive")
        @JsonProperty("source_type") String sourceType,
        @Pattern(regexp = "github|gitlab|gitee|generic_git|local|skillmesh") String provider,
        @Size(max = 512) @JsonProperty("repository_url") String repositoryUrl,
        @Size(max = 512) String subpath,
        @Pattern(regexp = "branch|tag|commit|default|local") @JsonProperty("ref_type") String refType,
        @Size(max = 200) @JsonProperty("ref_value") String refValue,
        @Size(max = 64) @JsonProperty("resolved_commit") String resolvedCommit,
        @Size(max = 200) @JsonProperty("license_expression") String licenseExpression,
        @Pattern(regexp = "unknown|detected|approved|restricted|incompatible")
        @JsonProperty("license_status") String licenseStatus) {}
