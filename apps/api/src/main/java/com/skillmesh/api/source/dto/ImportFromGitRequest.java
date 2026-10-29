package com.skillmesh.api.source.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 从 Git 仓库导入一个 Skill。 */
public record ImportFromGitRequest(
        @NotBlank @Size(max = 512) @JsonProperty("repository_url") String repositoryUrl,
        /** 仓库内的 Skill 子目录。不填则要仓库里只有一个候选。 */
        @Size(max = 512) String path,
        /** 分支、标签或 commit。不填用默认分支。 */
        @Size(max = 200) String ref,
        @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,63}$") String slug,
        @NotBlank @Size(max = 120) String name,
        @NotNull @Size(max = 4000) String description,
        @NotBlank String visibility,
        @Size(max = 30) List<@Size(max = 64) String> tags) {}
