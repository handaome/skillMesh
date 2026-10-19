package com.skillmesh.api.common;

/** 统一错误码。客户端只按 code 分支，不解析 detail 文本（详细设计 §2.1）。 */
public final class ErrorCodes {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String VERSION_CONFLICT = "VERSION_CONFLICT";
    public static final String DIGEST_MISMATCH = "DIGEST_MISMATCH";
    public static final String ARTIFACT_MISSING = "ARTIFACT_MISSING";
    public static final String SLUG_TAKEN = "SLUG_TAKEN";
    public static final String INTERNAL = "INTERNAL";

    private ErrorCodes() {}
}
