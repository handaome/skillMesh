package com.skillmesh.api.review.dto;

import java.util.UUID;

/**
 * 待审队列里的一项。
 *
 * 字段名用 snake_case 直接做 record 成分名——与 MemberResponse 一致，
 * Jackson 默认按成分名序列化，正好对上契约。
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
    ReviewSourceSummary source) {}
