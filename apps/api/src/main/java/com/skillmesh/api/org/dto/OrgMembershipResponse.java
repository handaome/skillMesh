package com.skillmesh.api.org.dto;

import java.util.UUID;

/**
 * 自己在某个组织里的成员关系。
 *
 * 放在 org 包而不是 auth 包：auth 的 /me 要用它，org 的服务层也要用它，
 * 放 auth 会让 org 反向依赖 auth，两个包绕成环。
 */
public record OrgMembershipResponse(UUID id, String slug, String name, String role) {}
