package com.skillmesh.api.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 刷新令牌。 */
public record RefreshRequest(
    @NotBlank(message = "缺少刷新令牌")
    @JsonProperty("refresh_token")
    String refreshToken) {
}
