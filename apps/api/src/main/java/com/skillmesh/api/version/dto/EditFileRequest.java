package com.skillmesh.api.version.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 改一个文件的内容。
 *
 * 编辑的产物是新版本——已发布的版本不可变（设计文档 §2）。
 */
public record EditFileRequest(
        /** 制品内的相对路径（正斜杠分隔）。 */
        @NotBlank @Size(max = 1024) String path,
        /** 文件的新内容。空串表示把文件清空。删除文件时不用给。 */
        @Size(max = 1048576) String content,
        /**
         * 二进制内容（base64）。
         *
         * 上传图片、字体这类文件时用这个：{@code content} 只能装 UTF-8 文本，
         * 而 Skill 的包里什么文件都可能有。
         */
        @JsonProperty("content_base64") @Size(max = 1398104) String contentBase64,
        /**
         * 做什么：replace（默认，改内容）/ create（新建）/ delete（删除）。
         */
        @Pattern(regexp = "replace|create|delete") String operation,
        /**
         * 这个文件是否可执行。不填就沿用原来的权限位。
         * 脚本的「可执行」在 zip 的外部属性里，删了它脚本就装成不可执行。
         */
        Boolean executable,
        /** 新版本号。不填就按已有的最大版本号加一个 patch。 */
        @Size(max = 128) String version,
        /** 发布通道。默认 stable。 */
        @Size(max = 16) String channel,
        /** 这次改了什么。进版本的 changelog。 */
        @Size(max = 8000) String changelog) {}
