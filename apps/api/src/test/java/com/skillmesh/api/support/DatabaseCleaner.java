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

    /**
     * 清空业务数据，保留 Flyway 建的表结构。
     *
     * 一条 TRUNCATE ... CASCADE：级联由 PG 按依赖顺序处理，
     * skills.latest_version 与 skill_versions 的循环外键（可延迟）也由它摆平。
     * 分成多条 DELETE 反而会撞上 skills.latest_version 这个循环外键。
     */
    public void clean() {
        jdbc.execute("truncate table artifacts, users cascade");
    }
}
