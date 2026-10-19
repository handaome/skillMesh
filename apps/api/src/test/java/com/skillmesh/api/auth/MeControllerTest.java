package com.skillmesh.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
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
}
