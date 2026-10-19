package com.skillmesh.api.version.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 对应 OpenAPI 的 SkillVersion。 */
public record SkillVersionResponse(
        UUID id,
        @JsonProperty("skill_id") UUID skillId,
        String version,
        String channel,
        String status,
        @JsonProperty("source_commit") String sourceCommit,
        ArtifactSummary artifact,
        List<ManifestEntry> manifest,
        ScanSummary scan,
        @JsonProperty("published_at") OffsetDateTime publishedAt,
        @JsonProperty("created_at") OffsetDateTime createdAt) {

    public record ArtifactSummary(
            UUID id,
            String sha256,
            @JsonProperty("size_bytes") long sizeBytes,
            @JsonProperty("content_type") String contentType,
            /** 清单摘要的签名，客户端用它验证内容没被换过。 */
            String signature,
            @JsonProperty("signature_key_id") String signatureKeyId,
            /** 清单摘要本身：签名和同步判重都以它为准。 */
            @JsonProperty("content_digest") String contentDigest) {}

    /** 逐文件清单。客户端用它算规范化摘要，判断内容是否真的变了。 */
    public record ManifestEntry(String path, String sha256, @JsonProperty("size_bytes") long sizeBytes) {}

    /** 最近一次扫描结论：装之前给用户看的风险来源。 */
    public record ScanSummary(
            @JsonProperty("scanner_name") String scannerName,
            @JsonProperty("scanner_version") String scannerVersion,
            String status,
            String summary,
            @JsonProperty("completed_at") OffsetDateTime completedAt,
            List<Finding> findings) {

        public record Finding(String path, int line, String rule, String detail) {}
    }
}
