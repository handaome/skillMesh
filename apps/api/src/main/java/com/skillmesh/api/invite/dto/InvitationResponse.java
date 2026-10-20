package com.skillmesh.api.invite.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 创建邀请的响应。token 只在这一刻出现，之后库里只有它的哈希。 */
public record InvitationResponse(
    UUID id,
    String organization_slug,
    String organization_name,
    String team_slug,
    String team_name,
    String org_role,
    String team_role,
    OffsetDateTime expires_at,
    String invite_url,
    String token) {}
