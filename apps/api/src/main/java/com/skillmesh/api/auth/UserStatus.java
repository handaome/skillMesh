package com.skillmesh.api.auth;

/**
 * 账号的状态。
 *
 * 常量名就是数据库里存的值、也是接口里传的值：小写。
 */
public enum UserStatus {

    /** 正常。 */
    active,

    /** 被停用了：登不进去。 */
    disabled,

    /** 待激活。 */
    pending
}
