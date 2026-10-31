package com.skillmesh.api.org;

/**
 * 组织的状态。
 *
 * 常量名就是数据库里存的值、也是接口里传的值：小写。
 */
public enum OrganizationStatus {

    /** 正常。 */
    active,

    /** 暂停：还能看，不能动。 */
    suspended,

    /** 归档。 */
    archived
}
