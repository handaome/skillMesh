package com.skillmesh.api.version.dto;

/** 撤回请求。理由会进审计流水，客户端下次同步时提示给用户。 */
public record RevokeVersionRequest(String reason) {}
