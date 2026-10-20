package com.skillmesh.api.org.dto;

import jakarta.validation.constraints.NotBlank;

/** 改角色请求体。角色词表由服务层校验（org/Roles），这里只管非空。 */
public record RoleRequest(@NotBlank String role) {}
