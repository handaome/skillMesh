package com.skillmesh.api.version.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** 对应 OpenAPI 的 UploadSession。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UploadSession(
        UUID id,
        String purpose,
        String status,
        Upload upload,
        @JsonProperty("expires_at") OffsetDateTime expiresAt,
        @JsonProperty("verified_sha256") String verifiedSha256,
        @JsonProperty("error_code") String errorCode) {

    public record Upload(String method, String url, Map<String, String> headers) {}
}
