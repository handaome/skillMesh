package com.skillmesh.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务的总开关。
 *
 * 调度框架本身常开：跑不跑由各个任务自己的开关决定——
 * 定时备份默认关（备份贵，每个副本都备份一遍是白费力气），
 * 回收站清理默认开（清理便宜且幂等）。
 *
 * 框架常开、任务各自开关，是为了加新任务不用动这里，
 * 也不会出现「关掉备份顺手把别的定时任务也关了」的事故。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
