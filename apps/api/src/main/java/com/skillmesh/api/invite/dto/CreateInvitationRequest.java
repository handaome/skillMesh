package com.skillmesh.api.invite.dto;

import com.skillmesh.api.org.Role;
import java.util.UUID;

/**
 * 建邀请的请求体。
 *
 * 角色由枚举认：owner 不能通过邀请拿到（只能由现任 owner 直接任命），
 * 那是服务层按 Roles.INVITABLE_ORG_ROLES 挡的；这里认不出来的取值直接 400。
 */
public record CreateInvitationRequest(
    UUID team_id,
    Role org_role,
    Role team_role,
    String subject) {}
