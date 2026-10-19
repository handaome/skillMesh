package com.skillmesh.api.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 备份与恢复验证（设计文档 §14）。
 *
 * 真导出一份、验一遍，再故意改坏一个文件，确认校验能报出来——
 * 光能导出不算数，能发现损坏才算。
 */
@SpringBootTest
@ActiveProfiles("test")
class BackupServiceTest {

    @Autowired private BackupService backup;

    @Test
    void 导出后校验通过文件被动过就报出来() throws Exception {
        Path dir = Files.createTempDirectory("skillmesh-backup-");
        var manifest = backup.export(dir);

        assertThat(manifest.tables()).isNotEmpty();
        assertThat(Files.exists(dir.resolve("manifest.json"))).isTrue();
        assertThat(backup.verify(dir)).as("刚导出的备份应当是干净的").isEmpty();

        // 改坏一张表的数据
        Path skills = dir.resolve("db").resolve("skills.ndjson");
        String content = Files.readString(skills, StandardCharsets.UTF_8);
        Files.writeString(skills, content + "{\"id\":\"tampered\"}\n", StandardCharsets.UTF_8);

        var problems = backup.verify(dir);
        assertThat(problems).isNotEmpty();
        assertThat(problems.toString()).contains("skills");
    }
}
