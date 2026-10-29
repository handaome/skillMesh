package com.skillmesh.api.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillmesh.api.auth.UserEntity;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 账号摘要：给管理员看的账号列表。
 *
 * 不含密码哈希——这个对象会直接进 JSON 响应。
 */
public record UserAccount(
    UUID id,
    String username,
    @JsonProperty("display_name") String displayName,
    String email,
    String status,
    String role,
    @JsonProperty("created_at") OffsetDateTime createdAt) {

    public static UserAccount of(UserEntity user) {
        return new UserAccount(user.getId(), user.getUsername(), user.getDisplayName(),
            user.getEmail(), user.getStatus(), user.getRole(), user.getCreatedAt());
    }
}
