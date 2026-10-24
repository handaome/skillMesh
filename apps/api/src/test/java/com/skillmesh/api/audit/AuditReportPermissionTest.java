package com.skillmesh.api.audit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.skill.SkillService;
import com.skillmesh.api.skill.dto.CreateSkillRequest;
import com.skillmesh.api.support.DatabaseCleaner;
import java.util.List;
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

/**
 * 客户端上报审计事件的权限（详细设计 §12）。
 *
 * 上报的 target 是客户端自报的：谁都能往别人的 Skill 流水里塞「安装完成」这类条目，
 * 审计台就分不出真假。服务端只放行三种关系——属主、该组织/团队成员、真装过这个 Skill。
 *
 * 这个测试用**两个真实用户**：realm 里的 dev-user 与 dev-user2
 * （infra/scripts/add-second-user.sh 建的就是第二个，密码 dev2）。
 * 只用数据库里手搓的 stranger 造不出「跟这个 Skill 没关系的人」——
 * 那样两个用户共用同一个令牌，测的还是同一个人。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditReportPermissionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;
    @Autowired private SkillService skills;

    private String auth;

    @BeforeEach
    void 准备() throws Exception {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
    }

    /**
     * 看得见但没关系的人不能往别人的流水里写。
     *
     * 公开 Skill 谁都看得见——只判「看得见」的话，任何登录用户都能往公开 Skill 的
     * 流水里塞条目。这里用第二个真实用户（dev-user2）来扮演「看得见但没关系的人」。
     */
    @Test
    void 看得见但没关系的人不能往别人的流水里写() throws Exception {
        UUID skillId = skills.create(currentUser(), new CreateSkillRequest("public-demo", "Public",
            "", "public", null, null, List.of(), Map.of(), null)).id();

        String other = "Bearer " + tokens.accessToken("dev-user2", "dev2");
        mockMvc.perform(post("/api/v1/audit/events")
                .header("Authorization", other).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "action", "install.completed", "target_type", "skill",
                    "target_id", skillId.toString()))))
            .andExpect(status().isForbidden());
    }

    /** 属主自己往自己的 Skill 流水里写：照常放行。 */
    @Test
    void 属主可以往自己的流水里写() throws Exception {
        UUID skillId = skills.create(currentUser(), new CreateSkillRequest("mine", "Mine",
            "", "private", null, null, List.of(), Map.of(), null)).id();

        mockMvc.perform(post("/api/v1/audit/events")
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "action", "install.completed", "target_type", "skill",
                    "target_id", skillId.toString()))))
            .andExpect(status().isCreated());
    }

    /** 第二个用户对别人的私有 Skill 连看都看不见，更别想写。 */
    @Test
    void 别人看不见的Skill更不能写() throws Exception {
        UUID skillId = skills.create(currentUser(), new CreateSkillRequest("secret", "Secret",
            "", "private", null, null, List.of(), Map.of(), null)).id();

        String other = "Bearer " + tokens.accessToken("dev-user2", "dev2");
        mockMvc.perform(post("/api/v1/audit/events")
                .header("Authorization", other).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "action", "install.completed", "target_type", "skill",
                    "target_id", skillId.toString()))))
            .andExpect(status().isForbidden());
    }

    /** 当前用户就是 dev-user 自己（令牌里的 sub 映射到库里那个用户）。 */
    private CurrentUser currentUser() throws Exception {
        String body = mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).path("user").path("id").asText());
        return new CurrentUser(id, "owner", "Owner", "owner@example.com");
    }
}
