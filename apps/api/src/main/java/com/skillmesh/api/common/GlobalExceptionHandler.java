package com.skillmesh.api.common;

import com.skillmesh.api.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String BASE = "https://skills.example.com/problems/";

    private final AuditService audit;

    public GlobalExceptionHandler(AuditService audit) {
        this.audit = audit;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Problem> handleApi(ApiException ex, HttpServletRequest req) {
        recordDenial(ex, req);
        return ResponseEntity.status(ex.status()).body(Problem.of(
            BASE + ex.code().toLowerCase().replace('_', '-'),
            ex.code(), ex.status(), ex.code(), ex.getMessage(), requestId(req), false));
    }

    /**
     * 权限被拒要留痕（设计文档 §13：权限被撤销 → 停止后续下载/上传并记录审计）。
     *
     * 集中在这里记，而不是让每个权限判断点自己调——那些点漏一个就少一条记录，
     * 而「谁被拒过」正是审计要回答的问题。
     * 404 也记：看不见某个 Skill 而去试，与看得见但没权限，是同一类试探。
     */
    private void recordDenial(ApiException ex, HttpServletRequest req) {
        if (ex.status() != 403 && ex.status() != 404) {
            return;
        }
        audit.record(null, "access.denied", "request", req.getRequestURI(),
            "denied", Map.of(
                "status", ex.status(),
                "code", ex.code(),
                "method", req.getMethod()));
    }

    /**
     * 路径参数类型不对（比如把截断的 ID 当成完整 UUID 传进来），
     * 这是调用方的错，不该以 500 的形式暴露内部异常类型。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Problem> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                HttpServletRequest req) {
        Problem body = Problem.of(BASE + "invalid-parameter", ErrorCodes.VALIDATION_FAILED,
            400, ErrorCodes.VALIDATION_FAILED,
            "参数格式不对: " + ex.getName() + " = " + ex.getValue(), requestId(req), false);
        return ResponseEntity.badRequest().body(body);
    }

    /** 请求体读不出来（编码坏了、JSON 坏了）也是调用方的错，不该 500。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Problem> handleUnreadable(HttpMessageNotReadableException ex,
                                              HttpServletRequest req) {
        Problem body = Problem.of(BASE + "invalid-body", ErrorCodes.VALIDATION_FAILED,
            400, ErrorCodes.VALIDATION_FAILED, "请求体读不出来，检查编码和 JSON 格式",
            requestId(req), false);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Problem> handleValidation(MethodArgumentNotValidException ex,
                                             HttpServletRequest req) {
        List<Problem.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new Problem.FieldError(fe.getField(), "INVALID", fe.getDefaultMessage()))
            .toList();
        Problem body = new Problem(BASE + "validation-failed", ErrorCodes.VALIDATION_FAILED,
            400, ErrorCodes.VALIDATION_FAILED, "请求字段校验失败", requestId(req), false, fields);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> handleOther(Exception ex, HttpServletRequest req) {
        log.error("未处理异常 request_id={}", requestId(req), ex);
        Problem body = Problem.of(BASE + "internal", "Internal Server Error", 500,
            ErrorCodes.INTERNAL, "服务内部错误", requestId(req), true);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private static String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-Id");
        return id != null && !id.isBlank() ? id : "req_" + UUID.randomUUID().toString().replace("-", "");
    }
}
