package com.skillmesh.api.audit;

/**
 * 审计事件的结果。
 *
 * 常量名就是数据库里存的值、也是接口里传的值：小写。
 */
public enum AuditOutcome {

    /** 成了。 */
    success,

    /** 被权限挡下了。 */
    denied,

    /** 出错了。 */
    failure
}
