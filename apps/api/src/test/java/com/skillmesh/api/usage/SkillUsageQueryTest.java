package com.skillmesh.api.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmesh.api.auth.UserEntity;
import com.skillmesh.api.auth.UserRepository;
import com.skillmesh.api.auth.UserStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调用统计的 SQL 要能跑通。
 *
 * 可见性条件与统计聚合都是拼出来的原生 SQL——拼错了要到运行时才炸，
 * 这里先跑一次，确认语句本身没问题（库里没有数据时返回空也算过）。
 *
 * 类上挂事务：幂等插入是 update 查询，没事务 Spring Data 不放行；
 * 顺带让这些测试数据自动回滚，不留在库里。
 */
@SpringBootTest
@Transactional
class SkillUsageQueryTest {

    @Autowired
    private SkillCallRecordRepository repository;

    @Autowired
    private UserRepository users;

    @Test
    void 统计查询能跑通() {
        assertThat(repository.stats(UUID.randomUUID(),
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(30), "%", 10)).isEmpty();
    }

    @Test
    void 流水查询能跑通() {
        assertThat(repository.findVisible(UUID.randomUUID(), null, null, 10)).isEmpty();
    }

    @Test
    void 按slug找Skill能跑通() {
        assertThat(repository.findVisibleBySlug(UUID.randomUUID(), "no-such-skill")).isEmpty();
    }

    @Test
    void 重复上报同一个幂等键只记一条() {
        // actor 传空：库里没有这个用户，外键过不去。幂等只看 event_id，与上报者无关。
        String eventId = "test-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        int first = repository.insertIgnore(eventId, now, null, null, null,
            "usage-test-skill", "claude-code", null, null);
        int second = repository.insertIgnore(eventId, now, null, null, null,
            "usage-test-skill", "claude-code", null, null);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
    }

    /**
     * 统计行要真的能读出来。
     *
     * 只断言「没报错」不够：库里没数据时查询返回空，读那一列的转换根本不会执行。
     * 上次就漏在这里——原生 SQL 里 max(timestamptz) 的返回值驱动给的是 Instant，
     * 投影声明成 OffsetDateTime 会报「Cannot project Instant to OffsetDateTime」，
     * 线上表现是接口一读就 500，测试却一直是绿的。
     */
    @Test
    void 统计行读得出来() {
        UUID owner = 建一个用户();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        repository.insertIgnore("test-" + UUID.randomUUID(), now, owner, null, null,
            "usage-test-skill", "claude-code", "/tmp/proj", "参数片段");

        var rows = repository.stats(owner, now.minusDays(30), "%", 10);

        assertThat(rows).hasSize(1);
        SkillCallRecordRepository.SkillUsageStatRow row = rows.get(0);
        assertThat(row.getSkill()).isEqualTo("usage-test-skill");
        assertThat(row.getCallCount()).isEqualTo(1);
        assertThat(row.getUserCount()).isEqualTo(1);
        assertThat(row.getLastUsedAt()).isNotNull();
    }

    /** 建一个上报者：统计的可见性靠 actor_user_id 匹配本人，没有这行数据看不见。 */
    private UUID 建一个用户() {
        UUID id = UUID.randomUUID();
        UserEntity entity = new UserEntity();
        entity.setId(id);
        entity.setOidcIssuer("https://probe.local");
        entity.setOidcSubject("usage-probe-" + id);
        entity.setDisplayName("统计探针用户");
        entity.setStatus(UserStatus.active);
        entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        users.saveAndFlush(entity);
        return id;
    }
}
