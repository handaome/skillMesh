package com.skillmesh.api.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 审计查询的 SQL 要能跑通。
 *
 * 可见性与审计权限两段条件是拼出来的原生 SQL——拼错了要到运行时才炸，
 * 这里先跑一次，确认语句本身没问题（库里没有数据时返回空也算过）。
 */
@SpringBootTest
class AuditQueryTest {

    @Autowired
    private AuditRepository repository;

    @Test
    void 我可见的流水查询能跑通() {
        assertThat(repository.findVisibleForUser(UUID.randomUUID(), null, null, null, 10))
            .isEmpty();
    }

    @Test
    void 单个Skill的流水查询能跑通() {
        assertThat(repository.findForSkillAfterCursor(UUID.randomUUID(), UUID.randomUUID(),
            null, null, org.springframework.data.domain.PageRequest.of(0, 10)))
            .isEmpty();
    }
}
