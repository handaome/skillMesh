package com.skillmesh.api.auth;

import java.util.UUID;

/**
 * 当前请求的用户。id 是数据库主键，subject 是 OIDC sub，status 是 users 表里的账号状态。
 *
 * role 是站内角色：admin 是站点管理员，user 是普通账号。
 */
public record CurrentUser(UUID id, String subject, String displayName, String email, String status,
                          String role) {

    /** 站内管理员：能建号、看账号列表。 */
    public boolean siteAdmin() {
        return "admin".equals(role);
    }
}
