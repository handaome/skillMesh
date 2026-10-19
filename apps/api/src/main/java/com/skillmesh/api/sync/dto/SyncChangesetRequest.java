package com.skillmesh.api.sync.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;

/**
 * 对应 OpenAPI 的 SyncChangesetRequest。
 *
 * 客户端提交「我基于哪个基线、改成了什么」，服务端拿它和云端现状比，
 * 判定是正常同步还是冲突（设计文档 §6.3、§12）。
 */
public record SyncChangesetRequest(
        @NotNull UUID skill_id,
        @NotBlank @Pattern(regexp = "upload|download|bidirectional") String direction,
        /** 上次同步时的基线摘要。第一次同步留空。 */
        @Pattern(regexp = "^$|^[a-f0-9]{64}$") String base_hash,
        /** 本机现在的内容摘要。 */
        @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String local_hash,
        @JsonProperty("local_path") String local_path,
        /** 客户端已经算好的逐文件清单，可空。 */
        List<ManifestEntry> manifest) {

    public record ManifestEntry(String path, String sha256, @JsonProperty("size_bytes") long sizeBytes) {}
}
