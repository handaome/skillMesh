package com.skillmesh.api.org.dto;

import com.skillmesh.api.org.Role;

/**
 * 改组织成员的请求体：角色与账号状态都能改，都能不给，但至少要改一项。
 *
 * 角色由 org/Role 枚举认（认不出来就是 400），账号状态由服务层校验
 * （org/OrgService）——这里只管「有没有说要改什么」。
 */
public record MemberUpdateRequest(Role role, String status) {}
