package com.skillmesh.api.version.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 改一个文件的内容。
 *
 * 编辑的产物是新版本——已发布的版本不可变（设计文档 §2）。
 */
public record EditFileRequest(
        /** 制品内的相对路径（正斜杠分隔）。 */
        @NotBlank @Size(max = 1024) String path,
        /** 文件的新内容。空串表示把文件清空。 */
        @Size(max = 1048576) String content,
        /** 新版本号。不填就按已有的最大版本号加一个 patch。 */
        @Size(max = 128) String version,
        /** 发布通道。默认 stable。 */
        @Size(max = 16) String channel,
        /** 这次改了什么。进版本的 changelog。 */
        @Size(max = 8000) String changelog) {}
