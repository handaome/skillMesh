package com.skillmesh.api.common;

/** 带 HTTP 状态与错误码的业务异常。 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 私有资源不可见时也返回 404，避免泄露资源存在性（详细设计 §2.1）。 */
    public static ApiException notFound(String what) {
        return new ApiException(404, ErrorCodes.NOT_FOUND, what + " 不存在或不可见");
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, ErrorCodes.FORBIDDEN, message);
    }

    public static ApiException versionConflict(String message) {
        return new ApiException(409, ErrorCodes.VERSION_CONFLICT, message);
    }
}
