package com.skillmesh.api.install;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * installations.agent 不再限定枚举值。
 *
 * 限死取值的话，企业内网自建的 Agent 装完登记不上：服务端报 400，
 * 而 CLI 那时把 400 吞了——装完看着成功，跨设备却看不到这条记录。
 * Agent 目录是本机配置的事，服务端不该替用户限定有哪些 Agent。
 */
@SpringBootTest
class InstallationAgentConstraintTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void agent不再限定枚举但仍要像个名字() {
        String clause = jdbc.queryForObject(
            "select check_clause from information_schema.check_constraints "
                + "where constraint_name = 'installations_agent_check'",
            String.class);

        assertThat(clause).doesNotContain("claude-code").doesNotContain("cursor");
        assertThat(clause).contains("length");
    }
}
