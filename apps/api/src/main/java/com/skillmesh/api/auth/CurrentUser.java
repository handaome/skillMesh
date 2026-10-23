package com.skillmesh.api.auth;

import java.util.UUID;

/**
 * 当前请求的用户。id 是数据库主键，subject 是 OIDC sub，status 是 users 表里的账号状态。
 */
public record CurrentUser(UUID id, String subject, String displayName, String email, String status) {

    /**
     * 只关心身份、不关心账号状态的老调用点。
     *
     * 新建的用户默认 active，跟 users 表的默认值一致。
     */
    public CurrentUser(UUID id, String subject, String displayName, String email) {
        this(id, subject, displayName, email, "active");
    }
}
