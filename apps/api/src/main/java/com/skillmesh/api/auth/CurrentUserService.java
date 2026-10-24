package com.skillmesh.api.auth;

import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 从 OIDC token 解析当前用户，首次访问时自动建档。 */
@Service
public class CurrentUserService {

    /** 距上次记录超过这个间隔，才算一次新的「登录」。 */
    private static final Duration LOGIN_RECORD_INTERVAL = Duration.ofMinutes(5);

    private final UserRepository users;

    public CurrentUserService(UserRepository users) {
        this.users = users;
    }

    @Transactional
    public CurrentUser current(Jwt jwt) {
        String issuer = jwt.getIssuer().toString();
        String subject = jwt.getSubject();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UserEntity user = users.findByOidcIssuerAndOidcSubject(issuer, subject)
            .orElseGet(() -> provision(issuer, subject, jwt, now));
        // 禁用要立刻生效：令牌还有效，但账号已经登不进来了。
        rejectIfDisabled(user);
        // 每个请求都写一次 users 行是纯浪费——远程库一次往返几十毫秒。
        // 只有真要记一次「登录」时才写：上次记录距今超过阈值，或压根没记过。
        if (shouldRecordLogin(user.getLastLoginAt(), now)) {
            user.setLastLoginAt(now);
            user.setUpdatedAt(now);
            users.save(user);
        }
        return new CurrentUser(user.getId(), subject, user.getDisplayName(), user.getEmail(),
            user.getStatus());
    }

    private UserEntity provision(String issuer, String subject, Jwt jwt, OffsetDateTime now) {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setOidcIssuer(issuer);
        user.setOidcSubject(subject);
        user.setDisplayName(displayName(jwt));
        user.setEmail(jwt.getClaimAsString("email"));
        user.setStatus("active");
        // 建档本身就是一次登录：顺手记上，省得后面再补一次写。
        user.setLastLoginAt(now);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return users.save(user);
    }

    /** 距上次记录的登录时间超过阈值，才算一次新的登录。 */
    private static boolean shouldRecordLogin(OffsetDateTime lastLoginAt, OffsetDateTime now) {
        return lastLoginAt == null
            || lastLoginAt.isBefore(now.minus(LOGIN_RECORD_INTERVAL));
    }

    /**
     * 被禁用的账号：令牌立刻失效，所有走 current() 的请求都被拒。
     *
     * 判据放在这里是因为这是唯一的强制点——每个控制器都要先过 current()。
     * 建档那档（provision）设的是 active，不受影响。
     */
    private static void rejectIfDisabled(UserEntity user) {
        if ("disabled".equals(user.getStatus())) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "账号已禁用");
        }
    }

    private static String displayName(Jwt jwt) {
        for (String claim : List.of("name", "preferred_username")) {
            String value = jwt.getClaimAsString(claim);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return jwt.getSubject();
    }
}
