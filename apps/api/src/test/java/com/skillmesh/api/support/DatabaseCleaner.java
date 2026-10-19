package com.skillmesh.api.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 测试用：清空测试库里的业务数据，保留 Flyway 建的表结构。
 * 测试连的是独立的 skillmesh_test 库，不会碰到应用数据。
 */
@Component
public class DatabaseCleaner {

    private final JdbcTemplate jdbc;

    public DatabaseCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void clean() {
        // artifacts 是 skill_versions 的父表，级联方向相反，单独清。
        jdbc.execute("truncate table artifacts, users cascade");
    }
}
