package com.skillmesh.api.review.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * 批量审核的请求。
 *
 * 一次最多 50 条：批量是给「同一批东西一起处理」用的，不是给脚本无限灌的。
 */
public record BatchDecideRequest(
    /** approve 或 reject。 */
    @NotBlank @Pattern(regexp = "approve|reject") String action,
    /** 待处理的版本。 */
    @NotEmpty @Size(max = 50) List<@Valid Item> items,
    /** 审核意见。 */
    @Size(max = 2000) String comment) {

    /** 一条待审版本。 */
    public record Item(
        @NotNull @JsonProperty("skill_id") UUID skillId,
        @NotBlank String version) {
    }
}
