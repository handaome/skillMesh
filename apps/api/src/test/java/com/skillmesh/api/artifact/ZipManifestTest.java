package com.skillmesh.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmesh.api.common.ApiException;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * 制品必须是 zip，且根目录得有 SKILL.md。
 *
 * 这条闸是被一份真实的坏制品逼出来的：Java 的 ZipInputStream 对不是 zip 的
 * 字节**不报错**，它扫不到本地文件头就返回空清单——一份 tar.gz 被当成「空制品」
 * 发布出去，客户端安装时才报 not a valid zip file。
 */
class ZipManifestTest {

    /** 打一个 zip。 */
    private static byte[] zip(String name, String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(content.getBytes("UTF-8"));
            zos.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void acceptsRealZip() throws Exception {
        byte[] bytes = zip("SKILL.md", "# demo");
        ZipManifest.requireZip(bytes);
    }

    @Test
    void rejectsGzipPayload() {
        // gzip 魔数 1f 8b：tar.gz、gzip 压过的任何东西都长这样
        byte[] bytes = {(byte) 0x1f, (byte) 0x8b, 0x08, 0x00, 0x00, 0x00};
        assertThatThrownBy(() -> ZipManifest.requireZip(bytes))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("zip");
    }

    @Test
    void rejectsEmptyPayload() {
        assertThatThrownBy(() -> ZipManifest.requireZip(new byte[0]))
            .isInstanceOf(ApiException.class);
    }

    @Test
    void findsSkillRootAtTopLevel() {
        List<Map<String, Object>> manifest = List.of(Map.of("path", "SKILL.md"));
        assertThat(ZipManifest.hasSkillRoot(manifest)).isTrue();
    }

    @Test
    void findsSkillRootOneLevelDown() {
        List<Map<String, Object>> manifest = List.of(Map.of("path", "demo/SKILL.md"));
        assertThat(ZipManifest.hasSkillRoot(manifest)).isTrue();
    }

    @Test
    void reportsMissingSkillRoot() {
        List<Map<String, Object>> manifest = List.of(Map.of("path", "README.md"));
        assertThat(ZipManifest.hasSkillRoot(manifest)).isFalse();
    }
}
