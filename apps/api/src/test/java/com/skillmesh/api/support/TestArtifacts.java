package com.skillmesh.api.support;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 测试里造 zip 制品用的小工具。 */
public final class TestArtifacts {

    private TestArtifacts() {}

    public static byte[] zipOf(String name, String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return out.toByteArray();
    }

    /** 多文件包：entry 名 → 正文。 */
    public static byte[] zipOf(Map<String, String> files) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zos.putNextEntry(new ZipEntry(file.getKey()));
                zos.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    public static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
