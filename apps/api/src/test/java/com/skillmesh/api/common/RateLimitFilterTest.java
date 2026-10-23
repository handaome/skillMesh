package com.skillmesh.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 限流（设计文档 §14）。
 *
 * 直接测过滤器本身，不起 Spring 上下文——测试之间共用上下文会让额度互相串。
 */
class RateLimitFilterTest {

    @Test
    void 超过额度返回429并带剩余次数头() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(3, 3, 60);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.setRemoteAddr("127.0.0.1");

        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse ok = new MockHttpServletResponse();
            filter.doFilter(request, ok, new NoopChain());
            assertThat(ok.getStatus()).isEqualTo(200);
            assertThat(ok.getHeader("X-RateLimit-Remaining")).isNotBlank();
        }

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(request, blocked, new NoopChain());
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(blocked.getContentAsString()).contains("RATE_LIMITED");
    }

    @Test
    void 写接口和读接口各算各的额度() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(10, 1, 60);
        MockHttpServletRequest read = new MockHttpServletRequest("GET", "/api/v1/skills");
        read.setRemoteAddr("10.0.0.9");
        filter.doFilter(read, new MockHttpServletResponse(), new NoopChain());
        filter.doFilter(read, new MockHttpServletResponse(), new NoopChain());

        MockHttpServletRequest write = new MockHttpServletRequest("POST", "/api/v1/skills");
        write.setRemoteAddr("10.0.0.9");
        filter.doFilter(write, new MockHttpServletResponse(), new NoopChain());
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(write, blocked, new NoopChain());
        assertThat(blocked.getStatus()).isEqualTo(429);
    }

    /**
     * 键表只增不减就是慢速内存泄漏——键由来源 IP 决定，攻击者换着 IP 就能把它撑大。
     *
     * 窗口设成 0 秒：每个窗口一建立就过期，下一次清扫就该收走，
     * 键表不该跟着请求数一直涨。
     */
    @Test
    void 过期窗口会被回收键表不一直涨() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(100, 100, 0);
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/skills");
            request.setRemoteAddr("10.0.0." + i);
            filter.doFilter(request, new MockHttpServletResponse(), new NoopChain());
        }
        assertThat(filter.trackedKeys()).isEqualTo(1);
    }

    private static final class NoopChain implements FilterChain {
        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                              jakarta.servlet.ServletResponse response) {
            // 放行即可：这里只关心限流有没有把请求拦下来
        }
    }
}
