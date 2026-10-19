package com.skillmesh.api.auth;

import java.util.UUID;

/** 当前请求的用户。id 是数据库主键，subject 是 OIDC sub。 */
public record CurrentUser(UUID id, String subject, String displayName, String email) {}
