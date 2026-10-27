package com.skillmesh.api.group.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * 建集合与改集合的请求。
 *
 * slug 跟 Skill 一样：小写字母数字开头，后跟小写字母数字或连字符。
 */
public record GroupRequest(
    @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,63}$") String slug,
    @NotBlank @Size(max = 120) String name,
    @Size(max = 1000) String description,
    /** private / public / team / organization。 */
    @NotBlank String visibility,
    UUID organization_id,
    UUID team_id,
    /** 成员 Skill。必须是当前用户可见的。 */
    @Size(max = 200) List<UUID> skill_ids) {
}
