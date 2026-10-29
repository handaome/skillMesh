package com.skillmesh.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 打进去的文件要和 CLI 那一套规则一致。
 *
 * 隐藏文件、凭据类文件、.skillmeshignore 命中的都不该进包：
 * 发出去的文件、算摘要的文件、扫密钥的文件必须是同一批。
 */
class SkillPackagerTest {

    /** 读出包里的文件路径。 */
    private static List<String> entries(byte[] zip) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    @Test
    void packsSkillFilesAndSkipsNoise(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("SKILL.md"), "# demo");
        Files.writeString(dir.resolve("helper.sh"), "echo hi");
        Files.writeString(dir.resolve(".skillmesh-install"), "marker");
        Files.writeString(dir.resolve(".env"), "TOKEN=secret");
        Files.writeString(dir.resolve("id_rsa"), "key");
        Files.createDirectories(dir.resolve("node_modules"));
        Files.writeString(dir.resolve("node_modules").resolve("x.js"), "1");
        Files.createDirectories(dir.resolve("scripts"));
        Files.writeString(dir.resolve("scripts").resolve("skip.log"), "1");
        Files.writeString(dir.resolve(".skillmeshignore"), "scripts/\n");

        List<String> names = entries(SkillPackager.pack(dir));

        assertThat(names).containsExactlyInAnyOrder("SKILL.md", "helper.sh");
    }
}
