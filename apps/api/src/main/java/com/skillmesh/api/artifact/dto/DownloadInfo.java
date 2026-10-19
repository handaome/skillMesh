package com.skillmesh.api.artifact.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/** 受控下载信息。url 是短时效的签名地址，不是长期公开地址（详细设计 §12）。 */
public record DownloadInfo(
        String url,
        String sha256,
        @JsonProperty("size_bytes") long sizeBytes,
        @JsonProperty("content_type") String contentType,
        @JsonProperty("expires_at") OffsetDateTime expiresAt) {}
