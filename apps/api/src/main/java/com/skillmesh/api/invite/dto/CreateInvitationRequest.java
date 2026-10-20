package com.skillmesh.api.invite.dto;

import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record CreateInvitationRequest(
    UUID team_id,
    @Pattern(regexp = "owner|admin|publisher|reviewer|member|viewer") String org_role,
    @Pattern(regexp = "admin|publisher|reviewer|member|viewer") String team_role,
    String subject) {}
