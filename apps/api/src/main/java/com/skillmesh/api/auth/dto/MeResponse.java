package com.skillmesh.api.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillmesh.api.org.dto.OrgMembershipResponse;
import java.util.List;
import java.util.UUID;

/** 对应 OpenAPI 的 MeResponse schema。 */
public record MeResponse(User user, List<OrgMembershipResponse> organizations) {

    /**
     * subject 是 OIDC 的 sub——邀请要绑到某个人时填的就是它。
     *
     * 回显自己的 sub，被邀请人才能把它告诉管理员；不然管理员只能瞎猜，
     * 填了邮箱/用户名，接收者点接受就是一句「这份邀请不是发给你的」。
     */
    public record User(UUID id,
                       @JsonProperty("display_name") String displayName,
                       String email,
                       String status,
                       String subject) {}
}
