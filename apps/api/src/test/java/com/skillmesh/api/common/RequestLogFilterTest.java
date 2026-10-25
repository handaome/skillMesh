package com.skillmesh.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 请求日志过滤器（设计文档 §13：日志应包含请求 ID 与耗时）。
 */
class RequestLogFilterTest {

    @Test
    void 响应头里带上requestId() throws Exception {
        RequestLogFilter filter = new RequestLogFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/skills");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new NoopChain());

        String requestId = response.getHeader("X-Request-Id");
        assertThat(requestId).isNotBlank();
    }

    @Test
    void 客户端自己带的requestId照用() throws Exception {
        RequestLogFilter filter = new RequestLogFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/skills");
        request.addHeader("X-Request-Id", "req_from_client");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new NoopChain());

        // 客户端报障时给的号就是服务端日志里那个号。
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("req_from_client");
    }

    @Test
    void 出错时也照常走完过滤器() throws Exception {
        RequestLogFilter filter = new RequestLogFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/skills");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain boom = (req, res) -> {
            throw new IllegalStateException("下游炸了");
        };
        try {
            filter.doFilter(request, response, boom);
        } catch (IllegalStateException expected) {
            // 异常照常往上抛：过滤器不该把异常吞掉。
        }
        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
    }

    /** 什么都不做的链：过滤器的事测完了。 */
    private static class NoopChain implements FilterChain {
        @Override
        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            // 什么都不做
        }
    }
}
