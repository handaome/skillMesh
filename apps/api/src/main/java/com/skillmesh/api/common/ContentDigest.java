package com.skillmesh.api.common;

import java.io.IOException;
import java.io.InputStream;
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
 *
 * SHA-256 的算法名与十六进制格式只有这一处：散着写迟早有一处换了编码或换了
 * 大小写，同一份内容在两个模块算出两个摘要。
 */
public final class ContentDigest {

    private static final char FIELD_SEP = (char) 0;

    /** 摘要截断后展示的长度：够认出是哪一份，又不至于占满一行。 */
    private static final int SHORT_LENGTH = 8;

    private ContentDigest() {}

    public static String of(List<Map<String, Object>> manifest) {
        StringBuilder sorted = new StringBuilder();
        manifest.stream()
            .sorted(Comparator.comparing(item -> String.valueOf(item.get("path")),
                PathOrder.BY_CODE_POINT))
            .forEach(item -> sorted.append(item.get("path")).append(FIELD_SEP)
                .append(item.get("sha256")).append('\n'));
        return sha256(sorted.toString());
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 字节的 SHA-256 十六进制摘要。 */
    public static String sha256(byte[] data) {
        return hex(newDigest().digest(data));
    }

    /**
     * 流的 SHA-256 十六进制摘要——边读边算，不把内容整个读进堆。
     *
     * 备份要校验的文件可能有几百兆，readAllBytes 会把堆撑爆。
     */
    public static String sha256(InputStream in) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    /** 摘要太长不好看，只留头几位。 */
    public static String shortDigest(String digest) {
        if (digest == null || digest.isEmpty()) {
            return "空";
        }
        return digest.length() > SHORT_LENGTH ? digest.substring(0, SHORT_LENGTH) : digest;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }
}
