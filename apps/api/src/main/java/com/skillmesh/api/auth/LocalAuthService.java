package com.skillmesh.api.auth;

import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内置账号：用户名 + 密码。
 *
 * 有了它，服务端不部署 Keycloak 也能用——这是「新增账户太繁琐」那条路的落点。
 * 走外部 IdP 的账号照旧能用，两套并存。
 */
@Service
public class LocalAuthService {

    private final UserRepository users;
    private final TokenService tokens;
    private final PasswordEncoder passwords = new BCryptPasswordEncoder();
    private final boolean allowRegister;

    public LocalAuthService(UserRepository users,
                            TokenService tokens,
                            @Value("${skillmesh.auth.allow-register:true}") boolean allowRegister) {
        this.users = users;
        this.tokens = tokens;
        this.allowRegister = allowRegister;
    }

    /** 注册：用户名唯一，密码哈希后存库。 */
    @Transactional
    public TokenService.AuthTokens register(String username, String password,
                                            String displayName, String email) {
        if (!allowRegister) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN,
                "这个服务端不开放自助注册，让管理员建号吧");
        }
        if (users.findByUsernameIgnoreCase(username).isPresent()) {
            throw new ApiException(409, ErrorCodes.USERNAME_TAKEN, "用户名已被占用");
        }
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwords.encode(password));
        user.setDisplayName(displayName == null || displayName.isBlank()
            ? username : displayName.trim());
        user.setEmail(email == null || email.isBlank() ? null : email.trim());
        user.setStatus("active");
        // 第一个内置账号是管理员：还没有别的内置账号时，谁先来谁管。
        // 看的是「内置账号有几个」而不是「表里有几个人」——
        // 走外部 IdP 登录的老账号不该占掉这个位置。
        user.setRole(users.countByUsernameNotNull() == 0 ? "admin" : "user");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user.setLastLoginAt(now);
        users.save(user);
        return tokens.issue(user);
    }

    /** 登录：用户名不对与密码不对返回同一个错，不告诉外人哪个用户名存在。 */
    @Transactional
    public TokenService.AuthTokens login(String username, String password) {
        UserEntity user = users.findByUsernameIgnoreCase(username).orElse(null);
        if (user == null || user.getPasswordHash() == null
            || !passwords.matches(password, user.getPasswordHash())) {
            throw new ApiException(401, ErrorCodes.INVALID_CREDENTIALS, "用户名或密码不对");
        }
        rejectIfDisabled(user);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        user.setLastLoginAt(now);
        user.setUpdatedAt(now);
        users.save(user);
        return tokens.issue(user);
    }

    /** 用刷新令牌换一对新令牌。 */
    @Transactional
    public TokenService.AuthTokens refresh(String refreshToken) {
        Jwt jwt = tokens.verifyRefresh(refreshToken);
        String uid = jwt.getClaimAsString("uid");
        UserEntity user = uid == null ? null : users.findById(UUID.fromString(uid)).orElse(null);
        if (user == null) {
            throw new ApiException(401, ErrorCodes.INVALID_CREDENTIALS, "刷新令牌无效");
        }
        rejectIfDisabled(user);
        return tokens.issue(user);
    }

    private static void rejectIfDisabled(UserEntity user) {
        if ("disabled".equals(user.getStatus())) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "账号已禁用");
        }
    }
}
