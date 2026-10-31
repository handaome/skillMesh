package com.skillmesh.api.review;

/**
 * 审核结论。
 *
 * {@code changes_requested} 与 {@code rejected} 分开：前者是「改改再来」，
 * 后者是「不批」——两条路后续动作不同。
 *
 * 常量名就是数据库里存的值、也是接口里传的值：小写。
 */
public enum ReviewDecision {

    /** 通过。 */
    approved,

    /** 不批。 */
    rejected,

    /** 改改再来。 */
    changes_requested
}
