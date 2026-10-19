package com.skillmesh.api.version.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** 对应 OpenAPI 的 CreateVersionResponse。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateVersionResponse(
        @JsonProperty("version_id") UUID versionId,
        String status,
        UploadTarget upload,
        @JsonProperty("artifact_id") UUID artifactId) {

    /** 直传对象存储的目标：签名地址 + 需要的请求头。 */
    public record UploadTarget(
            @JsonProperty("session_id") UUID sessionId,
            String method,
            String url,
            Map<String, String> headers,
            @JsonProperty("expires_at") OffsetDateTime expiresAt) {}
}
