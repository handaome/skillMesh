package com.skillmesh.api.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动时跑一次备份（设计文档 §14）。
 *
 * 用法：--skillmesh.backup.dir=D:/backup/skillmesh
 * 目录里会有 db/*.ndjson、objects/** 和 manifest.json，
 * 恢复前先跑一次校验（BackupService.verify）。
 */
@Component
@ConditionalOnProperty("skillmesh.backup.dir")
public class BackupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BackupRunner.class);

    private final BackupService backup;
    private final java.nio.file.Path dir;

    public BackupRunner(BackupService backup,
                        @org.springframework.beans.factory.annotation.Value(
                            "${skillmesh.backup.dir}") String dir) {
        this.backup = backup;
        this.dir = java.nio.file.Path.of(dir);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var manifest = backup.export(dir);
        var problems = backup.verify(dir);
        if (problems.isEmpty()) {
            log.info("备份已校验：{} 张表、{} 个对象 -> {}", manifest.tables().size(),
                manifest.objects().size(), dir);
            return;
        }
        log.error("备份校验发现 {} 个问题：{}", problems.size(), problems);
    }
}
