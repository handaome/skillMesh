package com.skillmesh.api.org.dto;

import com.skillmesh.api.org.Role;
import jakarta.validation.constraints.NotNull;

/** 改角色请求体。角色取值由枚举认（org/Role），认不出来就是 400。 */
public record RoleRequest(@NotNull Role role) {}
