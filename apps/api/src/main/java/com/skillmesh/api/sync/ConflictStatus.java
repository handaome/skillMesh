package com.skillmesh.api.sync;

/**
 * 一个冲突的处理状态。
 *
 * 常量名就是数据库里存的值、也是接口里传的值：小写。
 */
public enum ConflictStatus {

    /** 还没人管。 */
    open,

    /** 按本地那份解了。 */
    resolved_local,

    /** 按云端那份解了。 */
    resolved_cloud,

    /** 两边合起来的。 */
    resolved_merged,

    /** 不用管，销掉。 */
    dismissed
}
