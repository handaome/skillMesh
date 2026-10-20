package com.skillmesh.api.invite.dto;

import java.util.UUID;

public record AcceptInvitationResponse(
    UUID invitation_id,
    UUID organization_id,
    UUID team_id,
    String org_role,
    String team_role,
    String message) {}
