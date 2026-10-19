package com.skillmesh.api.version.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
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
        @NotNull @Size(max = 5000) List<ManifestEntry> manifest) {

    public record ManifestEntry(
            @NotBlank @Size(max = 1024) String path,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String sha256,
            @PositiveOrZero long size_bytes,
            boolean executable) {}
}
