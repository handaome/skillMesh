package com.skillmesh.api.auth;

import java.util.UUID;

/**
 * 当前请求的用户。id 是数据库主键，subject 是 OIDC sub，status 是 users 表里的账号状态。
 */
public record CurrentUser(UUID id, String subject, String displayName, String email, String status) {
}
