package com.skillmesh.api.invite.dto;

import com.skillmesh.api.org.Roles;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record CreateInvitationRequest(
    UUID team_id,
    // owner 不能通过邀请拿到，只能由现任 owner 直接任命。
    @Pattern(regexp = Roles.INVITABLE_ORG_ROLE_PATTERN) String org_role,
    @Pattern(regexp = Roles.TEAM_ROLE_PATTERN) String team_role,
    String subject) {}
