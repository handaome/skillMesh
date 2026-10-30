package com.skillmesh.api.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 同步上传的线上格式：客户端与服务端要对得上字段名。
 *
 * CLI 那边按这些名字收发（apps/cli/internal/apiclient/sync.go）。
 * 名字改了一边而没改另一边，症状是「传完了但服务端说文件没传完」——
 * 字段对不上时 Jackson 会静默丢值，错误要到更靠后的地方才炸出来。
 */
class SyncUploadWireContractTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 上传文件的声明与服务端的字段名对得上() throws Exception {
        String body = json.writeValueAsString(List.of(
            new SyncUploadService.FileSpec("SKILL.md", "abc", 3, "text/markdown")));

        assertThat(body).contains("\"path\":\"SKILL.md\"")
            .contains("\"sha256\":\"abc\"")
            .contains("\"size_bytes\":3")
            .contains("\"content_type\":\"text/markdown\"");
    }

    @Test
    void 开上传会话的回执客户端读得出来() throws Exception {
        SyncUploadService.UploadSlot slot = json.readValue(
            "{\"path\":\"SKILL.md\",\"upload_id\":\"u1\",\"upload_url\":\"/uploads/u1\","
                + "\"headers\":{\"content-type\":\"text/markdown\"}}",
            SyncUploadService.UploadSlot.class);

        assertThat(slot.path()).isEqualTo("SKILL.md");
        assertThat(slot.uploadId()).isEqualTo("u1");
        assertThat(slot.uploadUrl()).isEqualTo("/uploads/u1");
        assertThat(slot.headers()).containsEntry("content-type", "text/markdown");
    }

    @Test
    void 提交时带的期望基线服务端读得出来() throws Exception {
        SyncController.CommitRequest request = json.readValue(
            "{\"expected_revision\":\"deadbeef\"}", SyncController.CommitRequest.class);

        assertThat(request.expected_revision()).isEqualTo("deadbeef");
    }
}
