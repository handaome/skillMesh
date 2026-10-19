package com.skillmesh.api.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 接口限流（设计文档 §14「设置 API 请求频率限制」）。
 *
 * 按用户限；没登录的按来源 IP 限。写接口的额度比读接口低——上传和发布比搜索贵得多。
 *
 * ponytail: 进程内计数，多实例部署各算各的。要全局额度就换 Redis 令牌桶，
 * 这里先把「一个人狂刷不伤到别人」这层做了。
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final int readLimit;
    private final int writeLimit;
    private final long windowMillis;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(@Value("${skillmesh.rate-limit.read-per-minute:600}") int readLimit,
                            @Value("${skillmesh.rate-limit.write-per-minute:60}") int writeLimit,
                            @Value("${skillmesh.rate-limit.window-seconds:60}") long windowSeconds) {
        this.readLimit = readLimit;
        this.writeLimit = writeLimit;
        this.windowMillis = windowSeconds * 1000;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String key = subject(request);
        int limit = isWrite(request) ? writeLimit : readLimit;
        Window window = windows.compute(key, (ignored, existing) -> {
            long now = Instant.now().toEpochMilli();
            if (existing == null || now - existing.startedAt >= windowMillis) {
                return new Window(now, 1);
            }
            return new Window(existing.startedAt, existing.count + 1);
        });
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - window.count)));
        if (window.count > limit) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/problem+json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("""
                {"type":"https://skills.example.com/problems/rate-limited",\
                "title":"Too Many Requests","status":429,"code":"RATE_LIMITED",\
                "detail":"请求太频繁，稍后再试","retryable":true,"field_errors":[]}""");
            return;
        }
        chain.doFilter(request, response);
    }

    private String subject(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
            && !"anonymousUser".equals(authentication.getName())) {
            return "user:" + authentication.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private boolean isWrite(HttpServletRequest request) {
        return switch (request.getMethod()) {
            case "POST", "PUT", "PATCH", "DELETE" -> true;
            default -> false;
        };
    }

    /** 一个时间窗内的计数。
     *
     * ponytail: 定期清窗靠过期判断，不做后台清扫——键只在有人请求时才增长，
     * 空闲实例的 map 不会涨。 */
    private record Window(long startedAt, int count) {}
}
