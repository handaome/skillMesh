package com.skillmesh.api.artifact;

import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

/**
 * 改 zip 里的文件：替换、新增、删除。
 *
 * 用于「编辑已发布的 Skill 里的文件」：拿原制品的字节，改动若干个 entry，
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

    /** 一个待改动的文件。 */
    public record Change(String path, byte[] content, boolean executable) {}

    /**
     * 按给定的改动重打包。
     *
     * @param zip 原制品的字节
     * @param changes 要改的文件。{@code content} 为 null 表示删除这个文件。
     * @return 新 zip 的字节
     */
    public static byte[] apply(byte[] zip, java.util.List<Change> changes) {
        Map<String, Entry> entries = readEntries(zip);
        for (Change change : changes) {
            if (change.content() == null) {
                entries.remove(change.path());
                continue;
            }
            entries.put(change.path(),
                new Entry(change.content(), change.executable()));
        }
        return write(entries);
    }

    /**
     * 换掉 zip 里的一个文件，返回新 zip 的字节。
     *
     * @param executable 新文件是否可执行。null 表示沿用原文件的权限。
     */
    public static byte[] replace(byte[] zip, String path, byte[] content, Boolean executable) {
        Map<String, Entry> entries = readEntries(zip);
        Entry original = entries.get(path);
        boolean mode = executable != null ? executable
            : original != null && original.executable();
        entries.put(path, new Entry(content, mode));
        return write(entries);
    }

    /**
     * 某个文件在 zip 里是不是可执行的。
     *
     * 权限位存在 zip 的外部属性里，清单里没有——服务端重算清单时不算这一项。
     * 所以只能回原 zip 里查。
     */
    public static boolean isExecutable(byte[] zip, String path) {
        Entry entry = readEntries(zip).get(path);
        return entry != null && entry.executable();
    }

    /**
     * 读出 zip 里的文件与它们的权限。
     *
     * 用 {@link ZipFile} 而不是 {@code ZipArchiveInputStream}：后者按本地文件头
     * 顺序读，**不解析 central directory 里的 external attributes**，
     * 权限位读到的是 0。ZipFile 读 central directory，才看得到。
     */
    private static Map<String, Entry> readEntries(byte[] zip) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        try (SeekableInMemoryByteChannel channel = new SeekableInMemoryByteChannel(zip);
             ZipFile zf = new ZipFile(channel, "UTF-8")) {
            Enumeration<ZipArchiveEntry> all = zf.getEntries();
            while (all.hasMoreElements()) {
                ZipArchiveEntry entry = all.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                // unix mode 存在 zip 的外部属性里。Go 的 archive/zip 读的是
                // external attributes 的高 16 位，且 creator version 要是 UNIX。
                int mode = (int) ((entry.getExternalAttributes() >> 16) & 0xFFFF);
                boolean executable = (mode & 0111) != 0;
                try (InputStream in = zf.getInputStream(entry)) {
                    entries.put(entry.getName(), new Entry(in.readAllBytes(), executable));
                }
            }
        } catch (IOException e) {
            throw new ApiException(422, ErrorCodes.VALIDATION_FAILED,
                "原制品读不出来，不是合法的 zip");
        }
        return entries;
    }

    /** 写 zip。权限位从传入的 Entry 取。 */
    private static byte[] write(Map<String, Entry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(out)) {
            // 按路径排序写：与清单排序一致，两边对得上。
            for (Map.Entry<String, Entry> entry : new TreeMap<>(entries).entrySet()) {
                // Unix 权限 0755 / 0644。装到 Linux 上才是可执行的脚本。
                // 用 UnixModeZipEntry：creator version 得是 UNIX，
                // Go 那边才从 external attributes 读权限位。
                ZipArchiveEntry ze = new UnixModeZipEntry(entry.getKey(),
                    entry.getValue().executable() ? 0755 : 0644);
                ze.setSize(entry.getValue().content().length);
                zos.putArchiveEntry(ze);
                zos.write(entry.getValue().content());
                zos.closeArchiveEntry();
            }
            zos.finish();
        } catch (IOException e) {
            throw new ApiException(500, ErrorCodes.INTERNAL, "重新打包失败: " + e.getMessage());
        }
        return out.toByteArray();
    }

    /** zip 里的一个文件：内容与可执行位。 */
    private record Entry(byte[] content, boolean executable) {}
}
