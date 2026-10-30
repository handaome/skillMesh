package com.skillmesh.api.backup;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动时跑一次备份（设计文档 §14）。
 *
 * 用法：--skillmesh.backup.dir=D:/backup/skillmesh
 * 每次备份落在 {@code <目录>/<时间戳>/} 里，里面有 db/*.ndjson、objects/**
 * 与 manifest.json；恢复前先跑一次校验（BackupService.verify）。
 *
 * 启动时跑一次只是兜底——应用不重启就没有下一次备份，
 * 要定期备份就打开 {@code skillmesh.backup.schedule-enabled}（见 BackupScheduler）。
 */
@Component
@ConditionalOnProperty("skillmesh.backup.dir")
public class BackupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BackupRunner.class);

    private final BackupService backup;
    private final Path base;
    private final int keep;

    public BackupRunner(BackupService backup,
                        @Value("${skillmesh.backup.dir}") String dir,
                        @Value("${skillmesh.backup.keep:24}") int keep) {
        this.backup = backup;
        this.base = Path.of(dir);
        this.keep = keep;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (base.toString().isBlank()) {
            // 目录没配（或配成了空串）：空串会让 Path.of("") 指向当前工作目录，
            // 备份就落在应用的工作目录里——那不是谁想要的。
            log.warn("没配备份目录，跳过启动时的备份");
            return;
        }
        Path dir = backup.newRunDir(base);
        BackupService.BackupResult result = backup.exportAndVerify(dir);
        backup.pruneRuns(base, keep);
        if (result.ok()) {
            log.info("{}", result.summary());
            return;
        }
        log.error("{}", result.problemSummary());
    }
}
