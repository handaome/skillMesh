package com.skillmesh.api.sync;

/**
 * 一次同步任务的状态。
 *
 * 常量名就是数据库里存的值、也是接口里传的值：小写。
 */
public enum ChangesetStatus {

    /** 排队中。 */
    queued,

    /** 正在做。 */
    processing,

    /** 做完了。 */
    completed,

    /** 撞上冲突，卡在那儿等人处理。 */
    conflict,

    /** 被拒了。 */
    rejected,

    /** 失败了。 */
    failed,

    /** 被取消。 */
    cancelled
}
