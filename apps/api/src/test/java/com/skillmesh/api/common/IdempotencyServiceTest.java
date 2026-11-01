package com.skillmesh.api.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.UserStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 幂等键（设计文档 §4.3 API-001）。
 *
 * 同一个键重试：动作只做一次，第二次拿到上次的响应。
 * 动作失败：键要放开，不然重试会被「上一次还在处理中」挡在门外。
 */
@SpringBootTest
class IdempotencyServiceTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private com.skillmesh.api.auth.UserRepository users;

    private IdempotencyService service;
    private CurrentUser user;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(jdbc, json);
        // 幂等表的 user_id 有外键：得先有这个人。
        UUID id = UUID.randomUUID();
        com.skillmesh.api.auth.UserEntity entity = new com.skillmesh.api.auth.UserEntity();
        entity.setId(id);
        entity.setOidcIssuer("https://probe.local");
        entity.setOidcSubject("probe-" + id);
        entity.setDisplayName("探针用户");
        entity.setStatus(UserStatus.active);
        entity.setCreatedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        entity.setUpdatedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        users.saveAndFlush(entity);
        user = new CurrentUser(id, entity.getOidcSubject(), entity.getDisplayName(), null,
            UserStatus.active, "user");
    }

    @AfterEach
    void tearDown() {
        // user_id 上有外键指向 users，删用户会连带删掉键记录。
        jdbc.update("delete from users where id = ?", user.id());
    }

    @Test
    void 同一个键重试只做一次动作() {
        AtomicInteger calls = new AtomicInteger();
        String key = "idem-" + UUID.randomUUID();

        建好了 first = service.run(user, "POST /skills", key, Map.of("slug", "a"),
            () -> new 建好了(UUID.randomUUID(), "结果" + calls.incrementAndGet(),
                OffsetDateTime.now(ZoneOffset.UTC)), 建好了.class);
        建好了 retry = service.run(user, "POST /skills", key, Map.of("slug", "a"),
            () -> new 建好了(UUID.randomUUID(), "结果" + calls.incrementAndGet(),
                OffsetDateTime.now(ZoneOffset.UTC)), 建好了.class);

        assertThat(retry).isEqualTo(first);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void 同一个键配了不同的请求内容就报冲突() {
        String key = "idem-" + UUID.randomUUID();
        service.run(user, "POST /skills", key, Map.of("slug", "a"), () -> 建好了的一条(), 建好了.class);

        assertThatThrownBy(() -> service.run(user, "POST /skills", key, Map.of("slug", "b"),
            () -> 建好了的一条(), 建好了.class))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(409));
    }

    @Test
    void 动作失败之后键被放开能再来一次() {
        AtomicInteger calls = new AtomicInteger();
        String key = "idem-" + UUID.randomUUID();

        assertThatThrownBy(() -> service.run(user, "POST /skills", key, Map.of("slug", "a"),
            () -> {
                calls.incrementAndGet();
                throw new IllegalStateException("建资源失败");
            }, 建好了.class))
            .isInstanceOf(IllegalStateException.class);

        建好了 retry = service.run(user, "POST /skills", key, Map.of("slug", "a"),
            () -> new 建好了(UUID.randomUUID(), "结果" + calls.incrementAndGet(),
                OffsetDateTime.now(ZoneOffset.UTC)), 建好了.class);

        assertThat(retry.name()).isEqualTo("结果2");
    }

    private static 建好了 建好了的一条() {
        return new 建好了(UUID.randomUUID(), "结果", OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 响应体长什么样：跟接口的 DTO 一样是 record，还带时间字段——
     * 重试时要能把上次存下来的 JSON 原样读回来，这两样都得认。
     */
    private record 建好了(UUID id, String name, OffsetDateTime createdAt) {}
}
