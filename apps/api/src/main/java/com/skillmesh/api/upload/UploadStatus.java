package com.skillmesh.api.upload;

/**
 * 上传会话的状态。
 *
 * 常量名就是数据库里存的值、也是接口里传的值：小写。
 */
public enum UploadStatus {

    /** 会话建好了，等着传。 */
    created,

    /** 传完了，还没校验。 */
    uploaded,

    /** 校验中。 */
    verifying,

    /** 校验通过，已提升为正式对象。 */
    completed,

    /** 失败，详情在 error_code 一列。 */
    failed,

    /** 过期了：会话有时限，过了就作废。 */
    expired
}
