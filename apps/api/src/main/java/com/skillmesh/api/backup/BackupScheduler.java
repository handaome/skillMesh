package com.skillmesh.api.backup;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时备份（设计文档 §14：生产部署需要定期备份数据库与对象存储）。
 *
 * 默认不开：多副本部署时每个副本都跑一遍备份是白费力气，
 * 由部署方在一个实例上打开（{@code skillmesh.backup.schedule-enabled=true}）。
 * 每次跑完只留最近的若干份（{@code skillmesh.backup.keep}），
 * 备份占的正是最要紧的那块盘，不清理迟早塞满。
 */
@Component
@ConditionalOnProperty(name = "skillmesh.backup.schedule-enabled", havingValue = "true")
public class BackupScheduler {

    private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);

    private final BackupService backups;
    private final Path base;
    private final int keep;

    public BackupScheduler(BackupService backups,
                           @Value("${skillmesh.backup.dir}") String dir,
                           @Value("${skillmesh.backup.keep:24}") int keep) {
        this.backups = backups;
        this.base = Path.of(dir);
        this.keep = keep;
    }

    /**
     * 到点就备份一次。
     *
     * 间隔用毫秒数而不是 cron 表达式：cron 得按时区解释，
     * 服务器换个时区或夏令时切换，备份时间就悄悄变了。
     */
    @Scheduled(fixedDelayString = "${skillmesh.backup.interval-ms:3600000}",
        initialDelayString = "${skillmesh.backup.initial-delay-ms:600000}")
    public void run() {
        if (base.toString().isBlank()) {
            log.warn("没配备份目录，跳过定时备份");
            return;
        }
        try {
            Path dir = backups.newRunDir(base);
            BackupService.BackupResult result = backups.exportAndVerify(dir);
            backups.pruneRuns(base, keep);
            if (result.ok()) {
                log.info("定时备份：{}", result.summary());
            } else {
                log.error("定时备份校验没过：{}", result.problemSummary());
            }
        } catch (Exception e) {
            log.error("定时备份失败: {}", e.getMessage(), e);
        }
    }
}
