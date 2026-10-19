package com.skillmesh.api.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 内容的规范化摘要：把「路径 + 文件摘要」排序后拼起来再哈希。
 *
 * 必须和 CLI 的 skillscan.Digest 同口径——服务端算的云端摘要要和客户端的本地摘要
 * 可比，否则同步判重和签名验证都会错位。
 */
public final class ContentDigest {

    private static final char FIELD_SEP = (char) 0;

    private ContentDigest() {}

    public static String of(List<Map<String, Object>> manifest) {
        StringBuilder sorted = new StringBuilder();
        manifest.stream()
            .sorted(Comparator.comparing(item -> String.valueOf(item.get("path"))))
            .forEach(item -> sorted.append(item.get("path")).append(FIELD_SEP)
                .append(item.get("sha256")).append('\n'));
        return sha256(sorted.toString());
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
