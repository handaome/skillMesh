package com.skillmesh.api.config;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开配置：给网页端与 CLI 登录前要知道的东西。
 *
 * issuer 与 client_id 原来写死在控制台源码里（{@code apps/web/src/auth/pkce.ts}），
 * 换企业 IdP 就得改代码重新打包——部署文档写的是「只认 OIDC issuer，代码不用改」，
 * 对网页端并不成立。这里把这两个值交给服务端，网页端登录前取一次即可。
 *
 * local_auth 说明服务端支持内置账号（用户名 + 密码），register_open 说明能不能自助注册。
 * issuer 为空串就是没接外部 IdP，此时只能用内置账号登录。
 *
 * 只有这些：密钥、连接串、内部地址一律不出现在这里。
 */
@RestController
@RequestMapping("/api/v1/config")
public class PublicConfigController {

    private final String issuer;
    private final String clientId;
    private final boolean registerOpen;

    public PublicConfigController(@Value("${skillmesh.oidc.issuer:}") String issuer,
                                  @Value("${skillmesh.oidc.client-id:}") String clientId,
                                  @Value("${skillmesh.auth.allow-register:true}") boolean registerOpen) {
        this.issuer = issuer == null ? "" : issuer.trim();
        this.clientId = clientId == null ? "" : clientId.trim();
        this.registerOpen = registerOpen;
    }

    @GetMapping("/public")
    public Map<String, Object> publicConfig() {
        return Map.of(
            "issuer", issuer,
            "client_id", clientId,
            "local_auth", true,
            "register_open", registerOpen);
    }
}
