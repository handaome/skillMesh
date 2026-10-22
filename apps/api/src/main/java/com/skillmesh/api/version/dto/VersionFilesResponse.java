package com.skillmesh.api.version.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 某版本里的文件清单。
 *
 * 路径、大小、摘要都是服务端从真 zip 算出来的，不是发布方自填的那份。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VersionFilesResponse(
        String version,
        @JsonProperty("file_count") int fileCount,
        List<FileEntry> files) {

    /**
     * 一个文件。
     *
     * @param path 制品内的相对路径（正斜杠分隔）
     * @param sizeBytes 解压后的大小
     * @param sha256 文件内容的摘要
     */
    public record FileEntry(
            String path,
            @JsonProperty("size_bytes") long sizeBytes,
            String sha256) {}
}
