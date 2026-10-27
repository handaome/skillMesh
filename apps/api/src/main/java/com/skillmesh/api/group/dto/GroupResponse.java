package com.skillmesh.api.group.dto;

import java.util.List;
import java.util.UUID;

/**
 * 一个集合的对外形状。
 *
 * installed_count 只算「我装了几个」——本人可见的数据。
 * 团队级的覆盖率（谁在哪台机器上装了多少）需要另开权限，不在这里混进来。
 */
public record GroupResponse(
    UUID id,
    String slug,
    String name,
    String description,
    String visibility,
    int member_count,
    int installed_count,
    List<Member> members) {

    /** 集合里的一个 Skill。 */
    public record Member(
        UUID id,
        String slug,
        String name,
        String latest_version,
        boolean installed) {
    }
}
