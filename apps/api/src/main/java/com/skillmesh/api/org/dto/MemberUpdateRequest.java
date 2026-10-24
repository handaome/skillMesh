package com.skillmesh.api.org.dto;

/**
 * 改组织成员的请求体：角色与账号状态都能改，都能不给，但至少要改一项。
 *
 * 两个字段的口径都由服务层校验（org/Roles 与 org/OrgService），
 * 这里只管「有没有说要改什么」。
 */
public record MemberUpdateRequest(String role, String status) {}
