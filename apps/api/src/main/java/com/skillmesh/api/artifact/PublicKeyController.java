package com.skillmesh.api.artifact;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 制品签名的公钥。客户端拿它验证下载到的内容（设计文档 §10）。 */
@RestController
@RequestMapping("/api/v1/keys")
public class PublicKeyController {

    private final SigningService signing;

    public PublicKeyController(SigningService signing) {
        this.signing = signing;
    }

    @GetMapping("/verify")
    public Map<String, Object> verifyKey() {
        return Map.of(
            "algorithm", "Ed25519",
            "key_id", signing.keyId(),
            "public_key", signing.publicKeyBase64(),
            "enabled", signing.enabled());
    }
}
