package com.skillmesh.api.auth;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * 测试侧通过 Keycloak direct grant 取真实 access token。
 * 不 mock JWT——真实签名 + 真实 JWKS 校验才能证明鉴权链路是通的。
 */
@Component
public class TestTokens {

    private final String issuer;
    private final String clientId;
    private final String username;
    private final String password;

    public TestTokens(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${skillmesh.test.client-id:skillmesh-test}") String clientId,
            @Value("${skillmesh.test.username:dev-user}") String username,
            @Value("${skillmesh.test.password:dev}") String password) {
        this.issuer = issuer;
        this.clientId = clientId;
        this.username = username;
        this.password = password;
    }

    public String accessToken() {
        RestTemplate rt = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", clientId);
        form.add("username", username);
        form.add("password", password);

        ResponseEntity<Map> resp = rt.postForEntity(
            issuer + "/protocol/openid-connect/token",
            new HttpEntity<>(form, headers), Map.class);
        if (resp.getStatusCode() != HttpStatus.OK || resp.getBody() == null) {
            throw new IllegalStateException("取测试 token 失败: " + resp.getStatusCode());
        }
        return (String) resp.getBody().get("access_token");
    }
}
