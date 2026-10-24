package com.skillmesh.api.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmesh.api.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 审计流水按 Skill 查时那条 join 走得动索引。
 *
 * `skills.id` 是 uuid、`audit_events.target_id` 是 text，转成 text 之后走不了
 * skills 的主键索引——这条 join 只能把 skills 整表扫一遍。
 * V7 迁移给 skills 加了表达式索引，这条用例钉住它真的建出来了。
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditJoinIndexTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleaner cleaner;

    @BeforeEach
    void 准备() {
        cleaner.clean();
    }

    @Test
    void skills的id转text有表达式索引() {
        String expression = jdbc.queryForObject(
            "select pg_get_expr(indexprs, indrelid) from pg_index "
                + "where indexrelid = 'idx_skills_id_text'::regclass",
            String.class);

        assertThat(expression).as("索引表达式是 id::text").isEqualTo("(id)::text");
    }
}
