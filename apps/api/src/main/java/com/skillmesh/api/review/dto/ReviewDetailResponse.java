package com.skillmesh.api.review.dto;

import com.skillmesh.api.diff.ArtifactDiffResult;
import java.util.List;
import java.util.Map;

/** 审核详情：待审项 + SKILL.md 正文 + 变更说明 + 扫描命中 + 文件级差异。 */
public record ReviewDetailResponse(
    PendingReviewItem item,
    String skill_md,
    String changelog,
    List<Map<String, Object>> findings,
    ArtifactDiffResult diff) {}
