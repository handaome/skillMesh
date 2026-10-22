package com.skillmesh.api.version.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 某个文件的正文。
 *
 * 二进制文件不返回内容（{@code content} 为 null），只报大小——把乱码塞给
 * 浏览器既没意义，也可能被当成脚本渲染。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VersionFileContentResponse(
        String version,
        String path,
        @JsonProperty("size_bytes") long sizeBytes,
        String sha256,
        /** 文件是不是二进制。二进制时 {@code content} 为 null。 */
        boolean binary,
        /** 文本内容。二进制文件为 null。 */
        String content) {}
