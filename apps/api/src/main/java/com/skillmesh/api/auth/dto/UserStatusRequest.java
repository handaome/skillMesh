package com.skillmesh.api.auth.dto;

import com.skillmesh.api.auth.UserStatus;
import jakarta.validation.constraints.NotNull;

/**
 * 禁用或启用账号的请求体。
 *
 * status 直接是枚举：认不出来的取值由 Jackson 挡在门外，报 400。
 */
public record UserStatusRequest(@NotNull UserStatus status) {}
