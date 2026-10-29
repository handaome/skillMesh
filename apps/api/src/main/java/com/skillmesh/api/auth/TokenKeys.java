package com.skillmesh.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 内置账号令牌的签名密钥。
 *
 * 单独拆出来是因为解码器（SecurityConfig）与签发器（TokenService）都要用，
 * 而解码器本身是个 bean：让签发器反过来依赖解码器会绕成环。
 */
@Component
public class TokenKeys {

    private static final Logger log = LoggerFactory.getLogger(TokenKeys.class);

    /** HS256 要求密钥至少 256 位，短密钥按 SHA-256 派生到 32 字节。 */
    private static final int KEY_BYTES = 32;

    private final SecretKey key;

    public TokenKeys(@Value("${skillmesh.auth.jwt-secret:}") String configured) {
        String secret = configured == null ? "" : configured.trim();
        if (secret.isEmpty()) {
            // 没配密钥就用随机的一次性密钥：开发机上省事，代价是重启后
            // 已登录的令牌全部失效。生产必须配 SM_JWT_SECRET。
            secret = randomSecret();
            log.warn("skillmesh.auth.jwt-secret 没配，本次启动用的是随机密钥——"
                + "重启后所有已登录的令牌都会失效。生产环境请设置 SM_JWT_SECRET。");
        }
        this.key = new SecretKeySpec(derive(secret), "HmacSHA256");
    }

    public SecretKey secretKey() {
        return key;
    }

    private static byte[] derive(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("算不出 SHA-256，密钥派生失败", e);
        }
    }

    private static String randomSecret() {
        byte[] bytes = new byte[KEY_BYTES];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }
}
