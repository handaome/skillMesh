package com.skillmesh.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.skillmesh.api.support.DatabaseCleaner;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;

/**
 * 别每个请求都写一次 users 行。
 *
 * 远程库一次往返几十毫秒，每个请求都 UPDATE 一次 users 行是纯浪费。
 * 只有「距上次记录超过阈值」才算一次新登录时才写。
 */
@SpringBootTest
@ActiveProfiles("test")
class LoginWriteThrottleTest {

    private static final String ISSUER = "https://issuer.test";
    private static final String SUBJECT = "throttle-user";

    @Autowired private CurrentUserService currentUserService;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void 准备() {
        cleaner.clean();
    }

    @Test
    void 阈值内不重复记录登录时间() {
        OffsetDateTime recorded = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        jdbc.update("insert into users (oidc_issuer, oidc_subject, display_name, status, "
            + "last_login_at) values (?, ?, 'Throttle', 'active', ?)",
            ISSUER, SUBJECT, recorded);

        currentUserService.current(jwt());

        // PG 的时间戳精度到微秒，跟插入时的纳秒值不相等——比时间差才对。
        assertThat(lastLoginAt()).as("阈值内不重复记录登录时间")
            .isCloseTo(recorded, within(1, ChronoUnit.MILLIS));
    }

    @Test
    void 超过阈值才记录新的登录时间() {
        jdbc.update("insert into users (oidc_issuer, oidc_subject, display_name, status, "
            + "last_login_at) values (?, ?, 'Throttle', 'active', ?)",
            ISSUER, SUBJECT, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10));

        currentUserService.current(jwt());

        assertThat(lastLoginAt()).as("超过阈值才记录新的登录时间")
            .isAfter(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    }

    private OffsetDateTime lastLoginAt() {
        return jdbc.queryForObject(
            "select last_login_at from users where oidc_subject = ?",
            OffsetDateTime.class, SUBJECT);
    }

    /** 造一个令牌——只用到 issuer 与 subject，签名在这一层不参与。 */
    private Jwt jwt() {
        return Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .claim("iss", ISSUER)
            .subject(SUBJECT)
            .build();
    }
}
