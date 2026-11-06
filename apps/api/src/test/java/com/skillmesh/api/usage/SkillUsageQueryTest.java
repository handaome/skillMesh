package com.skillmesh.api.usage;

import static org.assertj.core.api.Assertions.assertThat;

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
}
