package com.skillmesh.api.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.UserEntity;
import com.skillmesh.api.auth.UserRepository;
import com.skillmesh.api.auth.UserStatus;
import com.skillmesh.api.common.Visibility;
import com.skillmesh.api.device.DeviceService;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SkillStatus;
import com.skillmesh.api.sync.dto.SyncChangesetRequest;
import com.skillmesh.api.sync.dto.SyncChangesetResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 同一个幂等键重复提交只算一次（设计文档 §4.3 SYNC-001）。
 *
 * 上传传到一半断了，客户端拿同一个键重试——服务端要认得出这是同一次提交，
 * 返回同一条变更集，而不是再同步一遍。
 */
@SpringBootTest
class SyncIdempotencyTest {

    @Autowired
    private SyncService syncService;

    @Autowired
    private DeviceService devices;

    @Autowired
    private SkillRepository skills;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate committed;
    private CurrentUser owner;
    private UUID skillId;
    private UUID deviceId;

    @BeforeEach
    void setUp() {
        committed = new TransactionTemplate(transactionManager);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        committed.executeWithoutResult(status -> {
            owner = newUser();
            skillId = newSkill();
        });
        deviceId = devices.register(owner, "probe-" + UUID.randomUUID(), "探针设备",
            "windows", "0.0.0").getId();
    }

    @AfterEach
    void tearDown() {
        committed.executeWithoutResult(status -> {
            jdbc.update("delete from sync_conflicts where changeset_id in "
                + "(select id from sync_changesets where skill_id = ?)", skillId);
            jdbc.update("delete from sync_changesets where skill_id = ?", skillId);
            jdbc.update("delete from sync_states where skill_id = ?", skillId);
            jdbc.update("delete from audit_events where target_id in (?, ?)",
                skillId.toString(), deviceId.toString());
            jdbc.update("delete from skills where id = ?", skillId);
            jdbc.update("delete from devices where id = ?", deviceId);
            jdbc.update("delete from users where id = ?", owner.id());
        });
    }

    @Test
    void 同一个幂等键重试只产生一条变更集() {
        String key = "idem-" + UUID.randomUUID();
        SyncChangesetRequest request = new SyncChangesetRequest(skillId, "upload", "",
            "d".repeat(64), "local/probe", List.of());

        SyncChangesetResponse first = syncService.submit(owner, deviceId, key, request);
        SyncChangesetResponse retry = syncService.submit(owner, deviceId, key, request);

        assertThat(retry.changesetId()).isEqualTo(first.changesetId());
        assertThat(jdbc.queryForObject(
            "select count(*) from sync_changesets where device_id = ? and idempotency_key = ?",
            Integer.class, deviceId, key)).isEqualTo(1);
    }

    private CurrentUser newUser() {
        UUID id = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setOidcIssuer("https://probe.local");
        user.setOidcSubject("probe-" + id);
        user.setDisplayName("探针属主");
        user.setStatus(UserStatus.active);
        user.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        user.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        users.saveAndFlush(user);
        return new CurrentUser(id, user.getOidcSubject(), user.getDisplayName(), null,
            UserStatus.active, "user");
    }

    private UUID newSkill() {
        SkillEntity entity = new SkillEntity();
        entity.setId(UUID.randomUUID());
        entity.setSlug("probe-sync-idem-" + UUID.randomUUID());
        entity.setName("探针技能");
        entity.setDescription("测试用");
        entity.setOwnerUserId(owner.id());
        entity.setVisibility(Visibility.PRIVATE);
        entity.setStatus(SkillStatus.active);
        entity.setTags(new String[0]);
        entity.setMetadataJson(java.util.Map.of());
        entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return skills.saveAndFlush(entity).getId();
    }
}
