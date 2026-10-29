package com.skillmesh.api.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 登录结果：访问令牌、刷新令牌与有效期（秒）。
 *
 * 字段名是 snake_case，跟 OIDC 的 token 响应一个口径——
 * 网页端那套 setTokens 与 CLI 读的是同一份形状，换登录方式不用改解析。
 */
public record AuthResponse(
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("refresh_token") String refreshToken,
    @JsonProperty("expires_in") long expiresIn,
    String username) {
}
