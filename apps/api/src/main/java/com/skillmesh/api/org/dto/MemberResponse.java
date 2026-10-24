package com.skillmesh.api.org.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 成员列表项。字段名用 snake_case，跟别的响应体一致。
 *
 * subject 是 OIDC 的 sub——邀请要绑到某个已有成员时填的就是它。
 * 管理员在花名册上直接抄，不用再问人。
 *
 * status 是账号状态（active/disabled），来自 users 表：
 * 被禁用的人还挂在花名册上，只是登不进来了。
 */
public record MemberResponse(
    UUID user_id,
    String display_name,
    String email,
    String role,
    OffsetDateTime joined_at,
    String subject,
    String status) {}
