package com.skillmesh.api.artifact;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 制品清单签名（设计文档 §10 制品完整性：可对发布清单签名并在客户端验证）。
 *
 * 签的是**内容摘要**，不是整个 zip——摘要已经能唯一代表内容，验它就够了。
 * 没配私钥就不签：空着比签一个验不了的强。
 */
@Service
public class SigningService {

    private static final Logger log = LoggerFactory.getLogger(SigningService.class);
    private static final String ALGORITHM = "Ed25519";

    private final PrivateKey privateKey;
    private final String publicKey;
    private final String keyId;

    /**
     * 公钥是**原始 32 字节**的 base64——客户端的 crypto/ed25519 直接能用。
     * 私钥是 PKCS#8 的 base64。两者都显式配置：JDK 的 Ed25519 私钥推不出公钥，
     * 与其在这里做花招，不如让部署方用 openssl 生成一对明确的密钥。
     */
    public SigningService(@Value("${skillmesh.signing.private-key:}") String privateKeyBase64,
                          @Value("${skillmesh.signing.public-key:}") String publicKeyBase64,
                          @Value("${skillmesh.signing.key-id:local-dev}") String keyId) {
        this.keyId = keyId;
        this.publicKey = publicKeyBase64 == null ? "" : publicKeyBase64.trim();
        PrivateKey parsed = parsePrivateKey(privateKeyBase64);
        if (parsed != null && this.publicKey.isEmpty()) {
            log.warn("配了签名私钥但没配公钥（skillmesh.signing.public-key），制品不会签名");
            parsed = null;
        }
        this.privateKey = parsed;
        if (parsed == null) {
            log.info("未配置签名密钥，制品不会签名");
        }
    }

    public boolean enabled() {
        return privateKey != null;
    }

    public String keyId() {
        return keyId;
    }

    public String publicKeyBase64() {
        return publicKey;
    }

    /**
     * 对内容摘要签名。返回 base64 签名；没启用时返回空串。
     *
     * 签的内容是 key_id + 摘要，换一把密钥签出来的结果就对不上了。
     */
    public String sign(String contentDigest) {
        if (privateKey == null) {
            return "";
        }
        try {
            java.security.Signature signature = java.security.Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update((keyId + "\n" + contentDigest).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("制品签名失败", e);
        }
    }

    /** 客户端验证用的公开信息。 */
    public record PublicKeyInfo(String algorithm, String keyId, String publicKey,
                                boolean enabled) {}

    private static PrivateKey parsePrivateKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64.trim());
            return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (Exception e) {
            log.warn("签名私钥解析不了，制品将不签名: {}", e.getMessage());
            return null;
        }
    }
}
