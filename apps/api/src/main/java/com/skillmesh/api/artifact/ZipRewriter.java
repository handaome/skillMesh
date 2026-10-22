package com.skillmesh.api.artifact;

import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

/**
 * 改 zip 里的一个文件。
 *
 * 用于「编辑已发布的 Skill 里的某个文件」：拿原制品的字节，换掉一个 entry，
 * 重新打包成新制品。**不是就地改**——版本不可变，编辑的产物是下一个版本。
 *
 * 口径与 CLI 的 packager 对齐：
 * - entry 名是相对路径，正斜杠分隔；
 * - 重打包后 entry 顺序按路径排（与清单的排序一致）；
 * - 目录项不写（CLI 打包时也不写）。
 *
 * 用 commons-compress 写而不是 {@code java.util.zip}：后者没有 unix mode，
 * 写出来的 entry 在 Go 那边权限是 0，装的时候被当成 0644——
 * 脚本就此丢掉可执行位。commons-compress 是项目里已有的传递依赖。
 */
public final class ZipRewriter {

    private ZipRewriter() {}

    /**
     * 换掉 zip 里的一个文件，返回新 zip 的字节。
     *
     * @param zip 原制品的字节
     * @param path 要改的路径（制品内的相对路径）
     * @param content 新内容
     * @param executable 新文件是否可执行。改内容不该顺手改权限——
     *                   原文件是不是可执行由调用方从原制品里查出来传进来。
     * @return 新 zip 的字节
     */
    public static byte[] replace(byte[] zip, String path, byte[] content, boolean executable) {
        Map<String, byte[]> entries = readEntries(zip);
        entries.put(path, content);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(out)) {
            // 按路径排序写：与清单排序一致，两边对得上。
            for (Map.Entry<String, byte[]> entry : new TreeMap<>(entries).entrySet()) {
                ZipArchiveEntry ze = new ZipArchiveEntry(entry.getKey());
                // Unix 权限 0755 / 0644。装到 Linux 上才是可执行的脚本。
                ze.setUnixMode(executable ? 0755 : 0644);
                ze.setSize(entry.getValue().length);
                zos.putArchiveEntry(ze);
                zos.write(entry.getValue());
                zos.closeArchiveEntry();
            }
            zos.finish();
        } catch (IOException e) {
            throw new ApiException(500, ErrorCodes.INTERNAL, "重新打包失败: " + e.getMessage());
        }
        return out.toByteArray();
    }

    /**
     * 读出 zip 里的所有文件。
     *
     * 重名 entry 取**最后一个**：安装器与清单都是后一个覆盖前一个，
     * 打包必须跟它们一致——否则新包里同一路径有两个 entry，而服务端
     * 会把重名包当非法制品拒掉。
     */
    private static Map<String, byte[]> readEntries(byte[] zip) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (InputStream in = new java.io.ByteArrayInputStream(zip);
             ZipInputStream zis = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries.put(entry.getName(), zis.readAllBytes());
            }
        } catch (IOException e) {
            throw new ApiException(422, ErrorCodes.VALIDATION_FAILED,
                "原制品读不出来，不是合法的 zip");
        }
        return entries;
    }
}
