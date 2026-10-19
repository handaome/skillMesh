package com.skillmesh.api.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 对应 OpenAPI 的 RegisterDeviceRequest。device_key 由客户端生成的稳定标识。 */
public record RegisterDeviceRequest(
        @NotBlank @Size(max = 128) String device_key,
        @Size(max = 200) String display_name,
        @Size(max = 64) String platform,
        @Size(max = 64) String cli_version) {}
