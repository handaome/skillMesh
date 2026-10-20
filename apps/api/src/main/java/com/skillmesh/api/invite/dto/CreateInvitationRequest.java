package com.skillmesh.api.invite.dto;

import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record CreateInvitationRequest(
    UUID team_id,
    // owner 不能通过邀请拿到，只能由现任 owner 直接任命。
    @Pattern(regexp = "admin|publisher|reviewer|member|viewer") String org_role,
    @Pattern(regexp = "admin|publisher|reviewer|member|viewer") String team_role,
    String subject) {}
