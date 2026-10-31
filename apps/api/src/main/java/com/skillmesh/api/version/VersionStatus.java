package com.skillmesh.api.version;

/**
 * 版本的状态。
 *
 * 常量名就是数据库里存的值、也是接口里传的值：小写。
 * 改名等于改数据，要改先写迁移。
 *
 * {@code scanning} 是保留档：扫描在转正的同一个事务里同步做完，
 * 没有可观察的中间态。真要它可达，得把扫描异步化，不值当。
 */
public enum VersionStatus {

    /** 草稿：还在改，没进审核流。 */
    draft,

    /** 扫描中。保留档，实际上不可达。 */
    scanning,

    /** 待审核。 */
    pending_review,

    /** 已发布。 */
    published,

    /** 审核没通过。 */
    rejected,

    /** 扫描判定有风险，扣住不发。 */
    quarantined,

    /** 被撤回。 */
    revoked,

    /** 被下架。 */
    yanked
}
