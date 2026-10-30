package com.skillmesh.api.backup;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时备份要有个调度器。没开定时备份时整个应用不开调度：
 * 少一个后台线程，也少一个「谁在定时跑什么」的疑问。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "skillmesh.backup.schedule-enabled", havingValue = "true")
public class BackupScheduleConfig {
}
