package com.skillmesh.api.artifact;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 从真 zip 算逐文件清单（路径 + 每个文件的 sha256）。
 *
 * 口径与 CLI 的 packager 一致：entry 名就是相对路径（正斜杠），
 * 摘要算的是文件内容而不是压缩后的字节，清单按路径排序。
 * 两边一致才能把服务端算的清单和 CLI 报的清单放在一起比。
 */
public final class ZipManifest {

    private ZipManifest() {}

    /**
     * 逐文件算摘要，按路径排序。
     *
     * 目录项跳过（CLI 打包时不写目录项）；文件内容按原样算摘要。
     */
    public static List<Map<String, Object>> of(byte[] zip) {
        List<Map<String, Object>> manifest = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip),
                StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] data = readAll(in);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("path", entry.getName());
                item.put("sha256", ArtifactService.sha256(data));
                item.put("size_bytes", (long) data.length);
                manifest.add(item);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读 zip 失败", e);
        }
        manifest.sort((left, right) ->
            String.valueOf(left.get("path")).compareTo(String.valueOf(right.get("path"))));
        return manifest;
    }

    private static byte[] readAll(ZipInputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
