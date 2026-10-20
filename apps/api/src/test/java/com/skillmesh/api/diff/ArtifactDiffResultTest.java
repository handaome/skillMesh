package com.skillmesh.api.diff;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 差异结果的 JSON 形状——前端与审核队列端点都按这个字段名取。 */
class ArtifactDiffResultTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 字段名是下划线的() throws Exception {
        ArtifactDiffResult result = new ArtifactDiffResult("1.0.0", false,
            List.of(FileChange.modified("SKILL.md", 8, 2, false)));

        String body = json.writeValueAsString(result);

        assertThat(body).contains("\"base_version\":\"1.0.0\"");
        assertThat(body).contains("\"truncated\":false");
        assertThat(body).contains("\"files\":[");
        assertThat(body).contains("\"path\":\"SKILL.md\"");
        assertThat(body).contains("\"kind\":\"modified\"");
        assertThat(body).contains("\"added\":8");
        assertThat(body).contains("\"removed\":2");
    }

    @Test
    void 空的差异结果也是合法JSON() throws Exception {
        assertThat(json.writeValueAsString(ArtifactDiffResult.empty()))
            .isEqualTo("{\"base_version\":\"\",\"truncated\":false,\"files\":[]}");
    }
}
