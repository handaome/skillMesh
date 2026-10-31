package com.skillmesh.api.recycle;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回收期到了就把条目清掉（设计文档 §6.4）。
 *
 * 以前只有看回收站的时候才顺带清（{@code RecycleService.list} 里先删再查）：
 * 没人看回收站，那些条目就一直躺在表里。
 * 现在有定时任务，到点自己清。
 *
 * 默认开着：清理很便宜（{@code expires_at} 上有索引），而且幂等——
 * 多副本部署时每个副本都跑一遍，多删的那些行本来也过期了。
 * 与定时备份不一样，备份默认关：备份贵，每个副本都备份是白费力气。
 */
@Component
@ConditionalOnProperty(name = "skillmesh.recycle.cleanup-enabled", havingValue = "true",
    matchIfMissing = true)
public class RecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecycleScheduler.class);

    private final RecycleFileRepository files;

    public RecycleScheduler(RecycleFileRepository files) {
        this.files = files;
    }

    /**
     * 到点就清一次过期条目。
     *
     * 间隔用毫秒数而不是 cron 表达式：cron 得按时区解释，
     * 服务器换个时区或夏令时切换，清理时间就悄悄变了。
     */
    @Scheduled(fixedDelayString = "${skillmesh.recycle.cleanup-interval-ms:3600000}",
        initialDelayString = "${skillmesh.recycle.cleanup-initial-delay-ms:600000}")
    @Transactional
    public void clean() {
        int removed = files.deleteExpired(OffsetDateTime.now(ZoneOffset.UTC));
        if (removed > 0) {
            log.info("回收站清理：清掉 {} 条过期条目", removed);
        }
    }
}
