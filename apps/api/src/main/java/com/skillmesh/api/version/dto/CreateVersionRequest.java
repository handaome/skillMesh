package com.skillmesh.api.version.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 对应 OpenAPI 的 CreateVersionRequest。 */
public record CreateVersionRequest(
        @NotBlank @Size(max = 128) String version,
        @NotBlank @Pattern(regexp = "stable|beta|dev") String channel,
        UUID source_id,
        @Size(max = 128) String source_commit,
        @Size(max = 8000) String changelog,
        @PositiveOrZero @Max(52428800) long size_bytes,
        @Min(1) @Max(5000) int file_count,
        @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String sha256,
        @NotNull @Size(max = 5000) List<ManifestEntry> manifest,
        // expected_base_revision 是客户端做决策时看到的云端已发布版本内容摘要。
        // 不传（或传空串）= 不校验基线（普通 publish 照旧）；
        // 传了但对不上 = 409 VERSION_CONFLICT，云端在这期间被别人动过，
        // 这次上传会把那份改动静默盖掉。
        @Pattern(regexp = "^$|^[a-f0-9]{64}$") String expected_base_revision) {

    public record ManifestEntry(
            @NotBlank @Size(max = 1024) String path,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String sha256,
            @PositiveOrZero long size_bytes,
            boolean executable) {

        /**
         * 从服务端算出来的清单项转过来。
         *
         * 清单由服务端算（ZipManifest.of），不是发布方自填的那份——
         * 两边口径要一致，免得审核页列的文件和真包对不上。
         */
        public static ManifestEntry from(Map<String, Object> item) {
            return new ManifestEntry(
                    String.valueOf(item.get("path")),
                    String.valueOf(item.get("sha256")),
                    ((Number) item.getOrDefault("size_bytes", 0)).longValue(),
                    Boolean.TRUE.equals(item.get("executable")));
        }
    }
}
