package com.skillmesh.api.group.dto;

import com.skillmesh.api.common.Visibility;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * 改集合的请求。
 *
 * 与建集合的 {@link GroupRequest} 分开：slug 建好后不能改（改了别人手里的引用就断了），
 * 可见性却能改（放出去或收回来）。合一个请求体的话，光改个名字也得带上
 * 一个必填的 visibility，想不改都不行。
 *
 * 字段为 null 表示不改。visible 是集合的唯一入口，归属与可见性一起给。
 */
public record UpdateGroupRequest(
    @Size(max = 120) String name,
    @Size(max = 1000) String description,
    /** private / public / team / organization。不给就不改可见性。 */
    Visibility visibility,
    UUID organization_id,
    UUID team_id,
    /** 成员 Skill。必须是当前用户可见的。 */
    @Size(max = 200) List<UUID> skill_ids) {
}
