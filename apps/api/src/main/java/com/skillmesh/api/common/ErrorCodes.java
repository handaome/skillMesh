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
    public static final String INVITATION_INVALID = "INVITATION_INVALID";
    /** 用户名或密码不对。登录失败统一用这个码，不细分是哪个错了。 */
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    /** 用户名已被占用。 */
    public static final String USERNAME_TAKEN = "USERNAME_TAKEN";
    /**
     * 请求太频繁或并发下载数满了。
     *
     * 不是业务错误，是「过会儿再来」——所以它不进业务分支，
     * 客户端按 HTTP 429 与 problem+json 的 retryable 字段处理就行。
     */
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String INTERNAL = "INTERNAL";

    private ErrorCodes() {}
}
