package com.skillmesh.api.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * 服务端扫描：认出真凭据，把它挡住，而且不把凭据本身说出去。
 *
 * 设计文档 §10、§4.3 SEC-001：Skill 里带着真密钥时按策略阻断；
 * 报出来的是「哪个文件第几行像 AWS Key」，不是密钥本身——
 * 扫描结论要落库、要看，密钥值一进流水就等于又抄了一份。
 */
class ArtifactScannerTest {

    private static final String SKILL_MD =
        "---\nname: probe\ndescription: 探针技能\n---\n\n# probe\n";

    /** AWS Access Key 的形状：AKIA 加 16 位大写字母数字。 */
    private static final String SECRET = "AKIAIOSFODNN7EXAMPLE";

    private final ArtifactScanner scanner = new ArtifactScanner();

    @Test
    void 扫出真凭据就挡住() {
        ScanResult result = scanner.scan(zip(Map.of(
            "SKILL.md", SKILL_MD,
            "deploy.sh", "aws_key=\"" + SECRET + "\"\n")));

        assertThat(result.status()).isEqualTo(ScanStatus.failed);
        assertThat(result.blocked()).isTrue();
        assertThat(result.findings()).extracting(ScanResult.Finding::rule)
            .contains("AWS Access Key");
    }

    @Test
    void 命中项里不出现密钥值() {
        ScanResult result = scanner.scan(zip(Map.of(
            "SKILL.md", SKILL_MD,
            "deploy.sh", "aws_key=\"" + SECRET + "\"\n")));

        assertThat(result.summary()).doesNotContain(SECRET);
        assertThat(result.findings()).allSatisfy(finding -> {
            assertThat(finding.detail()).doesNotContain(SECRET);
            assertThat(finding.path()).doesNotContain(SECRET);
        });
    }

    @Test
    void 干净的制品放行() {
        ScanResult result = scanner.scan(zip(Map.of("SKILL.md", SKILL_MD)));

        assertThat(result.status()).isEqualTo(ScanStatus.passed);
        assertThat(result.blocked()).isFalse();
    }

    /** 把几段文本打成一个 zip。 */
    private static ByteArrayInputStream zip(Map<String, String> files) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> file : new LinkedHashMap<>(files).entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }
}
