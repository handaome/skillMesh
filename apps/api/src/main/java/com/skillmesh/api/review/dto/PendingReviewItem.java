package com.skillmesh.api.review.dto;

import java.util.UUID;

/**
 * 待审队列里的一项。
 *
 * 字段名用 snake_case 直接做 record 成分名——与 MemberResponse 一致，
 * Jackson 默认按成分名序列化，正好对上契约。
 *
 * status 是版本自己的状态（draft / scanning / pending_review / published / rejected / quarantined），
 * 不是队列的筛选条件——前端靠它判断「这一版还能不能过审」。
 *
 * can_decide 与 can_approve 是服务端算的结论，不让前端自己推：审核权怎么判
 * （团队优先回落）、能不能审自己发的版本，这些规则前端 replicate 一遍就一定会漂。
 */
public record PendingReviewItem(
    UUID skill_id,
    String skill_slug,
    String skill_name,
    String version,
    String channel,
    String submitted_by,
    String submitted_at,
    ReviewScanSummary scan,
    ReviewSourceSummary source,
    String status,
    /** 我能不能做审核动作（放行或驳回）。 */
    boolean can_decide,
    /** 我能不能放行。自己发布的版本不能自己放行，驳回可以。 */
    boolean can_approve) {}
