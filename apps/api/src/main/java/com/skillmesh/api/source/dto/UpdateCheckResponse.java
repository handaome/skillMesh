package com.skillmesh.api.source.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** 对应 OpenAPI 的 UpdateCheckResponse。allowed_hosts 让客户端知道服务端放行哪些主机。 */
public record UpdateCheckResponse(
        @JsonProperty("repository_url") String repositoryUrl,
        String ref,
        String commit,
        @JsonProperty("allowed_hosts") List<String> allowedHosts) {}
