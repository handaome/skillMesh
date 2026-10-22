package com.skillmesh.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;

/** 重打包：改内容、加文件、删文件，保住权限位。 */
class ZipRewriterTest {

    @Test
    void 换掉一个文件其余不动() throws Exception {
        byte[] zip = zipOf(Map.of("a.txt", "AAA", "b/c.txt", "CCC"));

        byte[] out = ZipRewriter.replace(zip, "a.txt", "BBB".getBytes(StandardCharsets.UTF_8), null);

        assertThat(entries(out)).containsEntry("a.txt", "BBB");
        assertThat(entries(out)).containsEntry("b/c.txt", "CCC");
    }

    @Test
    void 能新建文件() throws Exception {
        byte[] zip = zipOf(Map.of("a.txt", "AAA"));

        byte[] out = ZipRewriter.apply(zip, List.of(
            new ZipRewriter.Change("new/x.md", "新文件".getBytes(StandardCharsets.UTF_8), false)));

        assertThat(entries(out)).containsEntry("new/x.md", "新文件");
        assertThat(entries(out)).containsKey("a.txt");
    }

    @Test
    void 能删除文件() throws Exception {
        byte[] zip = zipOf(Map.of("a.txt", "AAA", "b.txt", "BBB"));

        byte[] out = ZipRewriter.apply(zip, List.of(
            new ZipRewriter.Change("b.txt", null, false)));

        assertThat(entries(out)).containsKey("a.txt");
        assertThat(entries(out)).doesNotContainKey("b.txt");
    }

    /** 权限位在重打包后要保住——脚本丢了可执行位就装成普通文件了。 */
    @Test
    void 保住没动文件的可执行位() throws Exception {
        byte[] zip = zipWithMode(Map.of("run.sh", "echo", "doc.md", "说明"), "run.sh");

        byte[] out = ZipRewriter.replace(zip, "doc.md", "新说明".getBytes(StandardCharsets.UTF_8), null);

        assertThat(modeOf(out, "run.sh") & 0111).as("脚本的可执行位").isNotZero();
        assertThat(modeOf(out, "doc.md") & 0111).as("文档不该可执行").isZero();
    }

    /** 显式给可执行位时按给的来。 */
    @Test
    void 能改可执行位() throws Exception {
        byte[] zip = zipOf(Map.of("run.sh", "echo"));

        byte[] out = ZipRewriter.replace(zip, "run.sh", "echo".getBytes(StandardCharsets.UTF_8), true);

        assertThat(modeOf(out, "run.sh") & 0111).as("要变成可执行").isNotZero();
    }

    @Test
    void 查得出某文件是否可执行() throws Exception {
        byte[] zip = zipWithMode(Map.of("run.sh", "echo"), "run.sh");

        assertThat(ZipRewriter.isExecutable(zip, "run.sh")).isTrue();
        assertThat(ZipRewriter.isExecutable(zip, "nope.sh")).isFalse();
    }

    /** 重名 entry 取最后一个——安装器与清单都是后一个覆盖前一个。 */
    @Test
    void 重名entry取最后一个() throws Exception {
        byte[] zip = zipOf(Map.of("a.txt", "第一个"));
        // 手工造一个重名包
        byte[] dup = zipWithDuplicate(zip, "a.txt", "第二个");

        byte[] out = ZipRewriter.replace(dup, "b.txt", "B".getBytes(StandardCharsets.UTF_8), null);

        assertThat(entries(out)).containsEntry("a.txt", "第二个");
    }

    private static byte[] zipOf(Map<String, String> files) throws Exception {
        return zipWithMode(files, null);
    }

    private static byte[] zipWithMode(Map<String, String> files, String executable) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(out)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                // 跟生产代码一样用 UnixModeZipEntry：creator version 得是 UNIX，
                // Go 那边才读得到权限位。
                ZipArchiveEntry entry = new UnixModeZipEntry(file.getKey(),
                    file.getKey().equals(executable) ? 0755 : 0644);
                entry.setSize(file.getValue().getBytes(StandardCharsets.UTF_8).length);
                zos.putArchiveEntry(entry);
                zos.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeArchiveEntry();
            }
        }
        return out.toByteArray();
    }

    /** 造一个含重名 entry 的包：同路径两个 entry。 */
    private static byte[] zipWithDuplicate(byte[] base, String path, String content)
            throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(out)) {
            ZipArchiveEntry first = new ZipArchiveEntry(path);
            first.setUnixMode(0644);
            zos.putArchiveEntry(first);
            zos.write("第一个".getBytes(StandardCharsets.UTF_8));
            zos.closeArchiveEntry();
            ZipArchiveEntry second = new ZipArchiveEntry(path);
            second.setUnixMode(0644);
            zos.putArchiveEntry(second);
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeArchiveEntry();
        }
        return out.toByteArray();
    }

    private static Map<String, String> entries(byte[] zip) throws Exception {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        try (ZipArchiveInputStream zis = new ZipArchiveInputStream(
                new ByteArrayInputStream(zip), "UTF-8")) {
            ZipArchiveEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                result.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    /**
     * 读某个 entry 的 Unix 权限位。
     *
     * 从 central directory 的 external attributes 里取（高 16 位），
     * 用 {@link ZipFile} 读——{@code ZipArchiveInputStream} 不解析那儿的属性。
     */
    private static int modeOf(byte[] zip, String path) throws Exception {
        try (var channel = new org.apache.commons.compress.utils.SeekableInMemoryByteChannel(zip);
             var zf = new org.apache.commons.compress.archivers.zip.ZipFile(channel, "UTF-8")) {
            var all = zf.getEntries();
            while (all.hasMoreElements()) {
                var entry = all.nextElement();
                if (path.equals(entry.getName())) {
                    return (int) ((entry.getExternalAttributes() >> 16) & 0xFFFF);
                }
            }
        }
        return 0;
    }
}
