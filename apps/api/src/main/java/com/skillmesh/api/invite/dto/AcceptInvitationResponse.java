package com.skillmesh.api.invite.dto;

import com.skillmesh.api.org.Role;
import java.util.UUID;

public record AcceptInvitationResponse(
    UUID invitation_id,
    UUID organization_id,
    UUID team_id,
    Role org_role,
    Role team_role,
    String message) {}
