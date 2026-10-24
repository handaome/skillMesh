package com.skillmesh.api.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 限流过滤器在链里的位置（设计文档 §14）。
 *
 * 挂在 Spring Security 链里、BearerTokenAuthenticationFilter 之后、AuthorizationFilter 之前：
 * - 认得出人的按人计数；
 * - 没认出来的（未鉴权）按来源 IP 计数；
 * - 未鉴权的请求最后才由授权阶段拒掉——它照样消耗额度，刷量不白刷。
 *
 * 挂在链外当普通 servlet 过滤器的话，未鉴权的请求被 401 挡在限流之前，刷量完全不消耗额度。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitChainPositionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;

    @BeforeEach
    void 准备() {
        cleaner.clean();
    }

    /**
     * 未鉴权的请求也过限流。
     *
     * 请求会被授权阶段拒成 401——但限流器已经数过它了：
     * 响应带着 X-RateLimit-Remaining 就是证据。限流器若在授权之后，
     * 这个 401 就没有这些头（它压根没轮到执行）。
     */
    @Test
    void 未鉴权的请求也消耗额度() throws Exception {
        var response = mockMvc.perform(get("/api/v1/skills"))
            .andExpect(status().isUnauthorized())
            .andReturn().getResponse();

        assertThat(response.getHeader("X-RateLimit-Limit"))
            .as("未鉴权的请求也被限流器数过").isNotBlank();
        assertThat(response.getHeader("X-RateLimit-Remaining"))
            .as("未鉴权的请求也消耗额度").isNotBlank();
    }

    /**
     * 已登录的按人计数，不是按来源 IP。
     *
     * 第一个用户连发两次把额度扣掉，再看第二个用户：他的额度是**自己的**，
     * 不接着第一个的扣。按 IP 计数的话，第二个用户会看到少了两格的额度。
     */
    @Test
    void 已登录的按人计数不是按IP() throws Exception {
        String owner = "Bearer " + tokens.accessToken();
        String other = "Bearer " + tokens.accessToken("dev-user2", "dev2");

        // 第一个用户发两次：额度扣掉两格。
        mockMvc.perform(get("/api/v1/me").header("Authorization", owner))
            .andExpect(status().isOk());
        long ownerRemaining = Long.parseLong(mockMvc.perform(get("/api/v1/me")
                .header("Authorization", owner))
            .andExpect(status().isOk())
            .andReturn().getResponse().getHeader("X-RateLimit-Remaining"));

        // 第二个用户第一次请求：他有自己的桶。
        long otherRemaining = Long.parseLong(mockMvc.perform(get("/api/v1/me")
                .header("Authorization", other))
            .andExpect(status().isOk())
            .andReturn().getResponse().getHeader("X-RateLimit-Remaining"));

        assertThat(otherRemaining)
            .as("第二个用户有自己的额度，不接着第一个的扣").isGreaterThan(ownerRemaining);
    }
}
