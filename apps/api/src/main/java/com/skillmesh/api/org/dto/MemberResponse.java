package com.skillmesh.api.org.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 成员列表项。字段名用 snake_case，跟别的响应体一致。 */
public record MemberResponse(
    UUID user_id,
    String display_name,
    String email,
    String role,
    OffsetDateTime joined_at) {}
