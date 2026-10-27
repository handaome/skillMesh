package com.skillmesh.api.skill.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * 批量改标签的请求。
 *
 * 一次最多 100 条：批量是给「同一批东西一起处理」用的，不是给脚本无限灌的。
 * 标签的个数与单个标签的长度跟单条更新一样限制。
 */
public record BatchTagRequest(
    @NotEmpty @Size(max = 100) List<@NotNull UUID> ids,
    @NotNull @Size(max = 30) List<@Size(max = 64) String> tags) {
}
