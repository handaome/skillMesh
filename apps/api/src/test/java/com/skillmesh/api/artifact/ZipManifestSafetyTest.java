package com.skillmesh.api.artifact;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmesh.api.common.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;

/**
 * 归档里的符号链接与压缩炸弹要挡住。
 *
 * 符号链接能指向包外的路径，装到 Agent 目录时一解析就写出去了；
 * 压缩炸弹是包本身几 KB、解开几百 MB，把 CPU 和磁盘打满。
 */
class ZipManifestSafetyTest {

    @Test
    void 符号链接的包要拒() throws Exception {
        byte[] zip = zipWith("evil-link", "../../etc/passwd".getBytes(StandardCharsets.UTF_8),
            UnixStat.LINK_FLAG);

        assertThatThrownBy(() -> ZipManifest.requireSafeArchive(zip))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("符号链接");
    }

    @Test
    void 压缩炸弹要拒() throws Exception {
        byte[] zip = zipWith("big.bin", new byte[2 << 20], UnixStat.FILE_FLAG);

        assertThatThrownBy(() -> ZipManifest.requireSafeArchive(zip))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("倍");
    }

    @Test
    void 普通包放过() throws Exception {
        byte[] zip = zipWith("SKILL.md", "# 正常技能\n".getBytes(StandardCharsets.UTF_8),
            UnixStat.FILE_FLAG);

        assertThatCode(() -> ZipManifest.requireSafeArchive(zip)).doesNotThrowAnyException();
    }

    /**
     * 造一个 zip。
     *
     * 用 {@link UnixModeZipEntry} 而不是普通 entry：unix 权限位要 creator version
     * 是 UNIX 才作数，否则读回来是 0。
     */
    private byte[] zipWith(String name, byte[] content, int unixMode) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out)) {
            ZipArchiveEntry entry = new UnixModeZipEntry(name, unixMode);
            zip.putArchiveEntry(entry);
            zip.write(content);
            zip.closeArchiveEntry();
        }
        return out.toByteArray();
    }
}
