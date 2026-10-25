package com.skillmesh.api.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmesh.api.auth.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.skillmesh.api.support.DatabaseCleaner;

/**
 * 审计写入本身的行为（详细设计 §10、§11）。
 *
 * 盯两件容易坏的事：
 * 审计要独立事务（业务回滚时它也得留下），
 * 以及 device_id 这类请求属性要真落到列上。
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditRecordTest {

    @Autowired private AuditService audit;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    private CurrentUser owner;

    @BeforeEach
    void 准备() {
        cleaner.clean();
        owner = 造用户();
    }

    @Test
    void 记下的流水带得上deviceId() {
        UUID deviceId = 造设备(owner);

        audit.record(owner, "install.completed", "skill", "skill-1", "success",
            Map.of("version", "1.0.0"), deviceId);

        String stored = jdbc.queryForObject(
            "select device_id from audit_events where action = 'install.completed'",
            String.class);
        assertThat(stored).isEqualTo(deviceId.toString());
    }

    @Test
    void 不给deviceId时那一列留空() {
        audit.record(owner, "skill.publish", "skill", "skill-2", "success",
            Map.of("version", "1.0.0"));

        String stored = jdbc.queryForObject(
            "select device_id from audit_events where action = 'skill.publish'",
            String.class);
        assertThat(stored).isNull();
    }

    /**
     * 审计走独立事务：业务回滚了，流水还得在。
     *
     * 这是「记不上就等于没发生」与「动作已经发生了」的差别——
     * 审计要记的是已经发生的事，不是打算发生的事。
     */
    @Test
    void 业务回滚了审计仍然留下() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.executeWithoutResult(status -> {
                audit.record(owner, "sync.commit", "skill", "skill-3", "success",
                    Map.of("version", "1.0.0"));
                throw new IllegalStateException("业务失败，回滚");
            });
        } catch (IllegalStateException expected) {
            // 业务那侧失败了，正是要验的场景
        }

        Integer count = jdbc.queryForObject(
            "select count(*) from audit_events where action = 'sync.commit'", Integer.class);
        assertThat(count).as("业务回滚不该把审计一起带走").isEqualTo(1);
    }

    /** 造一个真实用户行——成员表对 users 有外键，光给个 UUID 插不进去。 */
    private CurrentUser 造用户() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, oidc_issuer, oidc_subject, display_name, status) "
            + "values (?, 'https://kc', ?, 'Owner', 'active')", id, "owner-" + id);
        return new CurrentUser(id, "owner-" + id, "Owner", null);
    }

    /** 造一个真实设备行——audit_events.device_id 对 devices 有外键。 */
    private UUID 造设备(CurrentUser user) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into devices (id, user_id, device_key, display_name) "
            + "values (?, ?, ?, 'test-machine')", id, user.id(), "key-" + id);
        return id;
    }
}
