package com.skillmesh.api.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Git 主机白名单与来源解析接口（设计文档 §7.3、§12、§14 的 SSRF 防护）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GitHostPolicyTest {

    @Autowired private GitHostPolicy policy;
    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;

    private String auth;

    @BeforeEach
    void 准备() {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
    }

    @Test
    void 只放行白名单里的https与ssh地址() {
        assertThat(policy.requireAllowed("https://github.com/owner/repo.git"))
            .isEqualTo("https://github.com/owner/repo.git");
        assertThat(policy.requireAllowed("ssh://git@github.com/owner/repo.git")).contains("github");

        assertThatThrownBy(() -> policy.requireAllowed("file:///etc/passwd"))
            .hasMessageContaining("只允许");
        assertThatThrownBy(() -> policy.requireAllowed("http://github.com/owner/repo.git"))
            .hasMessageContaining("只允许");
        assertThatThrownBy(() -> policy.requireAllowed("https://evil.example.com/x.git"))
            .hasMessageContaining("允许列表");
        assertThatThrownBy(() -> policy.requireAllowed("https://user:pass@github.com/x.git"))
            .hasMessageContaining("密码");
    }

    @Test
    void 指向内网的主机要被挡下来() {
        // 本地地址：白名单主机名解析到内网时同样要拒，这是防 SSRF 的关键一条
        assertThatThrownBy(() -> policy.requireAllowed("https://127.0.0.1/x.git"))
            .hasMessageContaining("允许列表");
        assertThatThrownBy(() -> policy.requireAllowed("https://localhost/x.git"))
            .hasMessageContaining("允许列表");
    }

    @Test
    void 解析接口对非白名单主机返回400() throws Exception {
        mockMvc.perform(post("/api/v1/sources/resolve")
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"repository_url\":\"https://evil.example.com/x.git\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
