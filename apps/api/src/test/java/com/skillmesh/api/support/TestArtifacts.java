package com.skillmesh.api.support;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

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

    /**
     * 造一个含重名 entry 的包：同一路径两个 entry，先无害后载荷。
     *
     * JDK 的 ZipOutputStream 不让写重名 entry（duplicate entry），
     * 只能先写两个不同名的，再把第二个的名字改回第一个——名字长度相同，结构不变。
     */
    public static byte[] zipWithDuplicateEntry(String path, String first, String second)
            throws Exception {
        String decoy = decoyOf(path);
        if (first.contains(decoy) || second.contains(decoy)) {
            throw new IllegalArgumentException("正文里不能出现诱饵名：" + decoy);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry(path));
            zos.write(first.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry(decoy));
            zos.write(second.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        byte[] zip = out.toByteArray();
        byte[] needle = decoy.getBytes(StandardCharsets.UTF_8);
        byte[] target = path.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i + needle.length <= zip.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (zip[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                System.arraycopy(target, 0, zip, i, target.length);
                i += needle.length - 1;
            }
        }
        return zip;
    }

    /** n 个空 entry 的包——测「文件数上限」用。 */
    public static byte[] zipOfEmptyEntries(int count) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (int i = 0; i < count; i++) {
                zos.putNextEntry(new ZipEntry("f" + i + ".txt"));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /**
     * 造一个带 Unix 可执行位的包：entry 名 → 正文，标记哪些可执行。
     *
     * java 的 ZipOutputStream 不设 unix mode，Go 的 archive/zip 读出来权限是 0——
     * 要测「可执行位在重打包后保住」，得先造一个真的带可执行位的包。
     */
    public static byte[] zipWithExecutable(Map<String, String> files, String executablePath)
            throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(out)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                ZipArchiveEntry entry = new ZipArchiveEntry(file.getKey());
                entry.setUnixMode(file.getKey().equals(executablePath) ? 0755 : 0644);
                entry.setSize(file.getValue().getBytes(StandardCharsets.UTF_8).length);
                zos.putArchiveEntry(entry);
                zos.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeArchiveEntry();
            }
        }
        return out.toByteArray();
    }

    /** 同长度的另一个名字：把最后一个字符换掉。 */
    private static String decoyOf(String path) {
        char last = path.charAt(path.length() - 1);
        return path.substring(0, path.length() - 1) + (last == 'x' ? 'y' : 'x');
    }

    public static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
