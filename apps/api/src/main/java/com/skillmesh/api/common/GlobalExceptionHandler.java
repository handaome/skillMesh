package com.skillmesh.api.common;

import com.skillmesh.api.audit.AuditOutcome;
import com.skillmesh.api.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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
    /** 429 的 Retry-After：告诉客户端多久后再来。 */
    private static final int RETRY_AFTER_SECONDS = 5;

    private final AuditService audit;

    public GlobalExceptionHandler(AuditService audit) {
        this.audit = audit;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Problem> handleApi(ApiException ex, HttpServletRequest req) {
        recordDenial(ex, req);
        // 429 是「过会儿再来」，客户端得能看出来该不该重试。
        boolean retryable = ex.status() == 429;
        Problem body = Problem.of(
            BASE + ex.code().toLowerCase().replace('_', '-'),
            ex.code(), ex.status(), ex.code(), ex.getMessage(), requestId(req), retryable);
        if (!retryable) {
            return ResponseEntity.status(ex.status()).body(body);
        }
        return ResponseEntity.status(ex.status())
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS))
            .body(body);
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
            AuditOutcome.denied, Map.of(
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
        // 枚举取值不对也算「读不出来」，但提示不能一样：
        // 「检查 JSON 格式」会把人引到错的方向——JSON 格式好好的，是某个值不对。
        String badValue = badEnumValue(ex);
        if (badValue != null) {
            Problem body = Problem.of(BASE + "invalid-body", ErrorCodes.VALIDATION_FAILED,
                400, ErrorCodes.VALIDATION_FAILED,
                badValue, requestId(req), false);
            return ResponseEntity.badRequest().body(body);
        }
        Problem body = Problem.of(BASE + "invalid-body", ErrorCodes.VALIDATION_FAILED,
            400, ErrorCodes.VALIDATION_FAILED, "请求体读不出来，检查编码和 JSON 格式",
            requestId(req), false);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 从反序列化异常里找出「哪个字段的取值不对」。
     *
     * 两条路径：
     * 枚举有自定义转换方法（{@code Visibility.of}）时，Jackson 包成
     * {@code ValueInstantiationException}；走内置枚举反序列化时是
     * {@code InvalidFormatException}。两种都得认。
     *
     * 字段名从 Jackson 的路径引用里取（比解析消息文本可靠）。
     * 取不到就返回 null——那说明真的是 JSON 坏了，按「读不出来」处理。
     */
    static String badEnumValue(Throwable ex) {
        Throwable cause = ex;
        // 找两层：Spring 包一层，Jackson 再包一层。
        for (int i = 0; i < 3 && cause != null; i++) {
            if (cause instanceof com.fasterxml.jackson.databind.exc.ValueInstantiationException
                || cause instanceof com.fasterxml.jackson.databind.exc.InvalidFormatException) {
                break;
            }
            cause = cause.getCause();
        }
        if (!(cause instanceof com.fasterxml.jackson.databind.JsonMappingException mapping)) {
            return null;
        }
        String name = null;
        for (com.fasterxml.jackson.databind.JsonMappingException.Reference ref : mapping.getPath()) {
            if (ref.getFieldName() != null) {
                name = ref.getFieldName();
            }
        }
        if (name == null) {
            return null;
        }
        if (cause instanceof com.fasterxml.jackson.databind.exc.InvalidFormatException invalid) {
            // Jackson 那条提示是英文的，还带着 Java 类名。自己拼一条中文的：
            // 哪个字段、收到了什么值、能取哪些。
            return name + " 的取值不对：" + invalid.getValue()
                + "（可用：" + acceptedValues(invalid) + "）";
        }
        String reason = cause.getCause() == null ? mapping.getOriginalMessage() : cause.getCause().getMessage();
        return name + " 的取值不对" + (reason == null || reason.isBlank() ? "" : "：" + reason);
    }

    /** 这个枚举有哪些可选值，按常量名排。取不到就说见文档，不硬凑。 */
    private static String acceptedValues(
            com.fasterxml.jackson.databind.exc.InvalidFormatException invalid) {
        Class<?> raw = invalid.getTargetType();
        Object[] constants = raw == null ? null : raw.getEnumConstants();
        if (constants == null) {
            return "见接口文档";
        }
        return java.util.Arrays.stream(constants).map(Object::toString).sorted().toList().toString();
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
