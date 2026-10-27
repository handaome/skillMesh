package com.skillmesh.api.skill.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * 批量归档（删除）的请求。
 *
 * 一次最多 100 条。删除是软删除：数据留着，可以还原。
 */
public record BatchArchiveRequest(
    @NotEmpty @Size(max = 100) List<@NotNull UUID> ids) {
}
