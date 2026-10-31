package com.skillmesh.api.source.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillmesh.api.common.Visibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 一次导入多个 Skill（Hermes 生态的仓库通常一个仓库几十个 skill）。
 */
public record ImportFromGitBatchRequest(
        @NotBlank @Size(max = 512) @JsonProperty("repository_url") String repositoryUrl,
        /** 仓库内的 Skill 子目录，一个一条。 */
        @NotEmpty @Size(max = 50) List<@Size(max = 512) String> paths,
        /** 分支、标签或 commit。不填用默认分支。 */
        @Size(max = 200) String ref,
        @NotNull Visibility visibility,
        @Size(max = 30) List<@Size(max = 64) String> tags) {
}
