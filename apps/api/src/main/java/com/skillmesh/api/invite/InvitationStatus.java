package com.skillmesh.api.invite;

/**
 * 邀请的状态。
 *
 * 常量名就是数据库里存的值、也是接口里传的值：小写。
 */
public enum InvitationStatus {

    /** 还没人接受。 */
    pending,

    /** 接受了。 */
    accepted,

    /** 撤回了。 */
    revoked,

    /** 过期了：邀请有时限。 */
    expired
}
