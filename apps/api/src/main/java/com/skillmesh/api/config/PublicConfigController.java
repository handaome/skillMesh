package com.skillmesh.api.config;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开配置：给网页端登录前要知道的东西。
 *
 * issuer 与 client_id 原来写死在控制台源码里（{@code apps/web/src/auth/pkce.ts}），
 * 换企业 IdP 就得改代码重新打包——部署文档写的是「只认 OIDC issuer，代码不用改」，
 * 对网页端并不成立。这里把这两个值交给服务端，网页端登录前取一次即可。
 *
 * 只有这两个值：密钥、连接串、内部地址一律不出现在这里。
 */
@RestController
@RequestMapping("/api/v1/config")
public class PublicConfigController {

    private final String issuer;
    private final String clientId;

    public PublicConfigController(@Value("${skillmesh.oidc.issuer:}") String issuer,
                                  @Value("${skillmesh.oidc.client-id:}") String clientId) {
        this.issuer = issuer == null ? "" : issuer.trim();
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    @GetMapping("/public")
    public Map<String, String> publicConfig() {
        return Map.of("issuer", issuer, "client_id", clientId);
    }
}
