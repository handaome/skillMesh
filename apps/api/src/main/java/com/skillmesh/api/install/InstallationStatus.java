package com.skillmesh.api.install;

/**
 * 安装记录的状态。
 *
 * 说的是「这台机器上那份东西现在怎么样」：文件没了、被人改过、
 * 设备报冲突、被策略挡住，都在这几档里。
 *
 * 常量名就是数据库里存的值、也是接口里传的值：小写。
 * 改名等于改数据，要改先写迁移。
 */
public enum InstallationStatus {

    /** 装好了，与云端一致。 */
    active,

    /** 本机那份文件没了。 */
    missing,

    /** 与云端对不上（有本地改动）。 */
    conflict,

    /** 卸载了，但记录留着。 */
    uninstalled,

    /** 被策略挡住，不给装。 */
    blocked
}
