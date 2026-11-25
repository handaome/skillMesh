package com.skillmesh.api.version;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 版本该落成什么状态，以及状态该带哪些时间戳（设计文档 §10.1）。
 *
 * 状态和时间戳是一体的：published 必带 published_by/published_at，
 * pending_review 必带 submitted_at（进队列这一刻，不是建版本那一刻）。
 * 状态定了，时间戳跟着定，别拆成两处各写一遍。
 *
 * 整包上传、同步提交、过审、送审是四条入口，状态规则却是同一条。
 * 各写各的迟早写出第二套口径——同步提交绕过审核队列、上传与过审
 * 留下的时间戳不一样，都是这么来的。规则只写一份。
 */
public final class VersionStatusResolver {

    private VersionStatusResolver() {
    }

    /**
     * 扫描与审核的结论 → 版本该落成什么状态。
     *
     * @param scanBlocked 服务端扫描是否命中阻断规则
     * @param needsReview 这个 Skill 的可见性是否要求人审
     */
    public static VersionStatus decide(boolean scanBlocked, boolean needsReview) {
        if (scanBlocked) {
            // 隔离：内容有害，与审核拒绝的 rejected 分开记（设计文档 §10.1）。
            return VersionStatus.quarantined;
        }
        return needsReview ? VersionStatus.pending_review : VersionStatus.published;
    }

    /**
     * 发布路径的结论落到版本上（上传转正、同步提交都走这里）。
     *
     * @param publishedBy 发布者；只有落成 published 时才用得上
     * @return 落定的状态，调用方接着按它分支
     */
    public static VersionStatus applyPublishDecision(SkillVersionEntity version,
                                                     boolean scanBlocked, boolean needsReview,
                                                     UUID publishedBy, OffsetDateTime now) {
        VersionStatus status = decide(scanBlocked, needsReview);
        switch (status) {
            case published -> publish(version, publishedBy, now);
            case pending_review -> submitForReview(version, now);
            default -> version.setStatus(status);
        }
        return status;
    }

    /** 落成已发布：带发布者与发布时间。 */
    public static void publish(SkillVersionEntity version, UUID publishedBy, OffsetDateTime now) {
        version.setStatus(VersionStatus.published);
        version.setPublishedBy(publishedBy);
        version.setPublishedAt(now);
    }

    /** 落成待审核：带进队列的时间。 */
    public static void submitForReview(SkillVersionEntity version, OffsetDateTime now) {
        version.setStatus(VersionStatus.pending_review);
        version.setSubmittedAt(now);
    }
}
