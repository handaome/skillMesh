package com.skillmesh.api.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 接口限流（设计文档 §14「设置 API 请求频率限制」）。
 *
 * 按用户限；没登录的按来源 IP 限。写接口的额度比读接口低——上传和发布比搜索贵得多。
 *
 * 这个过滤器挂在 Spring Security 链里（BearerTokenAuthenticationFilter 之后、
 * AuthorizationFilter 之前），见 SecurityConfig。带令牌的请求已经认出了人，按人计数；
 * 没带令牌的请求还没被授权阶段拒掉，按来源 IP 计数。挂在链外当普通 servlet 过滤器的话，
 * 未鉴权的请求会被 401 挡在限流之前，刷量完全不消耗额度。
 *
 * ponytail: 进程内计数，多实例部署各算各的。要全局额度就换 Redis 令牌桶，
 * 这里先把「一个人狂刷不伤到别人」这层做了。
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final int readLimit;
    private final int writeLimit;
    private final long windowMillis;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    /** 上次清扫的时刻，用来把清扫摊到整个窗口周期上。 */
    private volatile long lastSweepAt = System.currentTimeMillis();

    public RateLimitFilter(int readLimit, int writeLimit, long windowSeconds) {
        this.readLimit = readLimit;
        this.writeLimit = writeLimit;
        this.windowMillis = windowSeconds * 1000;
    }

    /** 距离当前窗口重置还有多少秒。最少 1 秒，让客户端有个明确的等待点。 */
    private long secondsUntilReset(long windowStart) {
        long elapsed = System.currentTimeMillis() - windowStart;
        long remaining = (windowMillis - elapsed) / 1000;
        return Math.max(1, remaining);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long now = System.currentTimeMillis();
        sweepExpired(now);
        String key = subject(request);
        int limit = isWrite(request) ? writeLimit : readLimit;
        // 0 或负数表示关闭这一类限流。单机自用部署不想被首次批量同步卡住时用。
        if (limit <= 0) {
            chain.doFilter(request, response);
            return;
        }
        Window window = windows.compute(key, (ignored, existing) -> {
            if (existing == null || now - existing.startedAt() >= windowMillis) {
                return new Window(now, 1);
            }
            return new Window(existing.startedAt(), existing.count() + 1);
        });
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - window.count())));
        if (window.count() > limit) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            // 告诉客户端等到窗口重置再试。给固定秒数没用：窗口还没翻过去，
            // 重试多少次都还是超。
            response.setHeader("Retry-After", String.valueOf(secondsUntilReset(window.startedAt())));
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

    /**
     * 清掉过期窗口。
     *
     * 键空间由来源 IP 决定，只增不减就是慢速内存泄漏。
     *
     * ponytail: 跟着窗口周期扫，一个周期最多一次——每次请求全表扫是把泄漏换成 CPU 浪费。
     * 键数上万时换 Caffeine 的 expireAfterWrite。
     */
    private void sweepExpired(long now) {
        if (now - lastSweepAt < windowMillis) {
            return;
        }
        lastSweepAt = now;
        long expiredBefore = now - windowMillis;
        windows.entrySet().removeIf(entry -> entry.getValue().startedAt() <= expiredBefore);
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

    /** 当前记着的键数。给测试看键表有没有回收。 */
    int trackedKeys() {
        return windows.size();
    }

    /** 一个时间窗内的计数。 */
    private record Window(long startedAt, int count) {}
}
