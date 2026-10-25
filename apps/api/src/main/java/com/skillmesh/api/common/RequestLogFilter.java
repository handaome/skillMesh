package com.skillmesh.api.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求日志：方法、路径、状态、耗时、request_id（设计文档 §13）。
 *
 * 「日志应包含请求 ID、Skill ID、版本、来源类型、动作、结果和耗时」——
 * 前四项（Skill ID、版本、来源类型、动作）是**业务动作**的字段，
 * 由审计流水记（见 AuditService）；这里记的是**请求本身**的痕迹：
 * 哪个接口、什么结果、花了多久。
 *
 * request_id 从请求头取，没有就生成一个——响应头里也带回去，
 * 客户端报障时把这个号给出来，服务端能直接定位到那一次请求。
 *
 * 不是 bean：任何 Filter bean 都会被 Spring Boot 自动注册成 servlet 过滤器，
 * 与 Security 链里挂的那一份重复，同一个请求会被数两遍。
 * 由 SecurityConfig 构造并挂上去（跟 RateLimitFilter 同一个理由）。
 */
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    /** 超过这个毫秒数的请求单独记一条 warn：慢请求要能看见。 */
    private static final long SLOW_MS = 1_000;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = requestId(request);
        response.setHeader("X-Request-Id", requestId);
        long started = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            long cost = System.currentTimeMillis() - started;
            int status = response.getStatus();
            if (cost >= SLOW_MS) {
                log.warn("{} {} -> {} （{}ms）request_id={}",
                    request.getMethod(), path(request), status, cost, requestId);
            } else {
                log.info("{} {} -> {} （{}ms）request_id={}",
                    request.getMethod(), path(request), status, cost, requestId);
            }
        }
    }

    /** 客户端给的 request_id 就用它；没有就自己生成一个。 */
    private static String requestId(HttpServletRequest request) {
        String id = request.getHeader("X-Request-Id");
        return id != null && !id.isBlank() ? id : "req_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 记路径而不是完整 URL：查询参数里可能有敏感值。 */
    private static String path(HttpServletRequest request) {
        return request.getRequestURI();
    }
}
