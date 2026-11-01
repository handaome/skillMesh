package com.skillmesh.api.invite.dto;

import com.skillmesh.api.invite.InvitationStatus;
import com.skillmesh.api.org.Role;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 邀请摘要：不含 token、不含被邀请人身份，给还没登录的人看。 */
public record InvitationSummary(
    UUID id,
    String organization_name,
    String team_name,
    Role org_role,
    Role team_role,
    InvitationStatus status,
    OffsetDateTime expires_at,
    OffsetDateTime created_at) {}
