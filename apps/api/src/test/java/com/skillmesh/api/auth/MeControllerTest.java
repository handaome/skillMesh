package com.skillmesh.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.skillmesh.api.common.RateLimitFilter;
import com.skillmesh.api.support.DatabaseCleaner;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;
    @Autowired private SecurityFilterChain filterChain;

    @BeforeEach
    void 准备() {
        cleaner.clean();
    }

    @Test
    void 无令牌返回401() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void 首次访问自动建档() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.display_name").value("Dev User"))
            .andExpect(jsonPath("$.user.status").value("active"))
            .andExpect(jsonPath("$.organizations").isArray());

        assertThat(jdbc.queryForObject(
            "select count(*) from users", Integer.class)).isEqualTo(1);
    }

    @Test
    void 二次访问复用同一用户() throws Exception {
        String token = tokens.accessToken();
        String first = mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
            .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
            .andReturn().getResponse().getContentAsString();

        String firstId = json.readTree(first).path("user").path("id").asText();
        String secondId = json.readTree(second).path("user").path("id").asText();
        assertThat(firstId).isEqualTo(secondId);
        assertThat(jdbc.queryForObject(
            "select count(*) from users", Integer.class)).isEqualTo(1);
    }

    /**
     * /me 回显自己的 OIDC subject。
     *
     * 邀请要绑到某个人时填的就是这个值——本人拿得到，才谈得上
     * 「把这份邀请发给你」；不然管理员只能瞎猜，填了邮箱/用户名，
     * 接收者点接受就是一句「这份邀请不是发给你的」。
     */
    @Test
    void me回显自己的oidcSubject() throws Exception {
        String body = mockMvc.perform(get("/api/v1/me")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(body).path("user").path("subject").asText())
            .isEqualTo(jdbc.queryForObject("select oidc_subject from users", String.class));
    }

    /**
     * 账号被禁用后，令牌立刻失效。
     *
     * 写死 status 的话这列就没人读——账号被禁用，客户端看到的还是 active。
     * 现在禁用是**真的禁用**：`CurrentUserService.current()` 是所有请求的强制点，
     * 被禁用的账号在那里就被拒，不再是「看得到自己被禁用但还能干活」。
     */
    @Test
    void 账号被禁用后令牌立刻失效() throws Exception {
        String token = tokens.accessToken();
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.status").value("active"));
        jdbc.update("update users set status = 'disabled'");

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
    }

    /**
     * 限流必须排在鉴权之后、授权之前。
     *
     * 挂在链外的话，未鉴权的请求被 401 挡在限流之前，刷量完全不消耗额度，
     * 「没登录的按来源 IP 限」就成了死代码。
     */
    @Test
    void 限流挂在鉴权之后授权之前() {
        List<Filter> filters = filterChain.getFilters();

        assertThat(indexOf(filters, BearerTokenAuthenticationFilter.class))
            .isLessThan(indexOf(filters, RateLimitFilter.class));
        assertThat(indexOf(filters, RateLimitFilter.class))
            .isLessThan(indexOf(filters, AuthorizationFilter.class));
    }

    private static int indexOf(List<Filter> filters, Class<? extends Filter> type) {
        for (int i = 0; i < filters.size(); i++) {
            if (type.isInstance(filters.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
