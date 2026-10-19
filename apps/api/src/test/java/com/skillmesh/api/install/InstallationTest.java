package com.skillmesh.api.install;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.support.DatabaseCleaner;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 安装实例登记（设计文档 §11 installations、§12）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InstallationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;

    private String auth;

    @BeforeEach
    void 准备() {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
    }

    @Test
    void 登记后能查到重复登记只更新不新增() throws Exception {
        UUID skillId = createSkill();
        UUID deviceId = registerDevice();

        String first = register(skillId, deviceId, "1.0.0");
        assertThat(json.readTree(first).path("installed_version").asText()).isEqualTo("1.0.0");

        // 同一台机器同一目录再登记一次 = 更新，不是第二条
        register(skillId, deviceId, "1.1.0");
        String body = mockMvc.perform(get("/api/v1/installations").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get(0).path("installed_version").asText())
            .isEqualTo("1.1.0");
    }

    @Test
    void 别人看不到我的安装记录() throws Exception {
        UUID skillId = createSkill();
        register(skillId, registerDevice(), "1.0.0");

        mockMvc.perform(get("/api/v1/installations").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void 非法枚举值返回400() throws Exception {
        UUID skillId = createSkill();
        mockMvc.perform(post("/api/v1/installations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "skill_id", skillId.toString(),
                    "agent", "claude-code",
                    "install_scope", "宇宙",
                    "target_path", "/tmp/x"))))
            .andExpect(status().isBadRequest());
    }

    private String register(UUID skillId, UUID deviceId, String version) throws Exception {
        return mockMvc.perform(post("/api/v1/installations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "skill_id", skillId.toString(),
                    "device_id", deviceId.toString(),
                    "agent", "claude-code",
                    "install_scope", "user",
                    "target_path", "C:/skills/demo",
                    "installed_version", version))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
    }

    private UUID registerDevice() throws Exception {
        String resp = mockMvc.perform(post("/api/v1/devices").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_key\":\"test-device\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).path("id").asText());
    }

    private UUID createSkill() throws Exception {
        String resp = mockMvc.perform(post("/api/v1/skills").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", "installed", "name", "installed",
                    "description", "", "visibility", "private"))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).path("id").asText());
    }
}
