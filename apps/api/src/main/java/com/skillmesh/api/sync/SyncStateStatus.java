package com.skillmesh.api.sync;

/**
 * 一台机器上一个 Skill 的同步状态。
 *
 * 说的是「本地与云端各走到哪儿了」，不是同步任务的状态
 * （任务的状态在 {@link ChangesetStatus}）。
 *
 * 常量名就是数据库里存的值、也是接口里传的值：小写。
 */
public enum SyncStateStatus {

    /** 两边一致。 */
    in_sync,

    /** 本地比云端新。 */
    local_ahead,

    /** 云端比本地新。 */
    cloud_ahead,

    /** 两边都改了同一个文件。 */
    conflict,

    /** 这条同步被按住了。 */
    paused,

    /** 同步出错，详情在 last_error_* 两列。 */
    error
}
