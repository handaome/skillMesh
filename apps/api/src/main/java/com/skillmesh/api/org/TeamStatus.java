package com.skillmesh.api.org;

/**
 * 团队的状态。
 *
 * 常量名就是数据库里存的值、也是接口里传的值：小写。
 */
public enum TeamStatus {

    /** 正常。 */
    active,

    /** 归档。 */
    archived
}
