package com.skillmesh.api.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** RFC 9457 problem+json 错误体。字段名按详细设计 §2.1 用 snake_case。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(
        String type,
        String title,
        int status,
        String code,
        String detail,
        @JsonProperty("request_id") String requestId,
        boolean retryable,
        @JsonProperty("field_errors") List<FieldError> fieldErrors) {

    public record FieldError(String field, String code, String message) {}

    public static Problem of(String type, String title, int status, String code,
                             String detail, String requestId, boolean retryable) {
        return new Problem(type, title, status, code, detail, requestId, retryable, List.of());
    }
}
