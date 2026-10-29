package com.skillmesh.api.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 *
 * 密码上限 72 是 BCrypt 的硬限制：超出的部分会被静默截断，
 * 两个不同的长密码可能撞成同一个哈希。
 */
public record RegisterRequest(
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度 3-32 个字符")
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]*$",
        message = "用户名只能有字母、数字、下划线和连字符，且以字母开头")
    String username,

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 72, message = "密码长度 8-72 个字符")
    String password,

    @Size(max = 64, message = "显示名最长 64 个字符")
    @JsonProperty("display_name")
    String displayName,

    @Email(message = "邮箱格式不对")
    @Size(max = 128, message = "邮箱最长 128 个字符")
    String email) {
}
