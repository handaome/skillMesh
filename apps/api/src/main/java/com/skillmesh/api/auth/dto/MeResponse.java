package com.skillmesh.api.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/** 对应 OpenAPI 的 MeResponse schema。 */
public record MeResponse(User user, List<OrgMembership> organizations) {

    public record User(UUID id,
                       @JsonProperty("display_name") String displayName,
                       String email,
                       String status) {}

    public record OrgMembership(UUID id, String slug, String name, String role) {}
}
