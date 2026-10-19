package com.skillmesh.api.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 测试用：清空测试 schema 里的业务数据，保留 Flyway 建的表结构。 */
@Component
public class DatabaseCleaner {

    private final JdbcTemplate jdbc;

    public DatabaseCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void clean() {
        jdbc.execute("truncate table skillmesh_test.users cascade");
    }
}
