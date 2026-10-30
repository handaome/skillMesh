package com.skillmesh.api.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * agentskills.io 规范校验。
 *
 * 拦的是「在 Hermes 这类按规范扫的 Agent 里不会被识别」的 skill——
 * 装上去等于没装。
 */
class SkillSpecValidatorTest {

    @Test
    void 不合规的都要说清缺什么() {
        assertThat(violation("# 只是正文\n")).contains("frontmatter");
        assertThat(violation("---\nname: x\ndescription: y\n")).contains("没有闭合");
        assertThat(violation("---\ndescription: y\n---\n正文\n")).contains("缺 name");
        assertThat(violation("---\nname: x\n---\n正文\n")).contains("缺 description");
        assertThat(violation("---\nname: x\ndescription: y\n---\n\n  \n")).contains("没有正文");
        assertThat(violation("---\nname: " + "a".repeat(65)
            + "\ndescription: y\n---\n正文\n")).contains("name");
        assertThat(violation("---\nname: x\ndescription: " + "b".repeat(1025)
            + "\n---\n正文\n")).contains("description");
    }

    @Test
    void 合规的放行() {
        assertThat(violation("---\nname: demo\ndescription: 演示用的 skill\n---\n\n# 演示\n"))
            .isNull();
    }

    @Test
    void 正文里的分隔线不该把frontmatter截断() {
        assertThat(violation("---\nname: demo\ndescription: 演示\n---\n\n# 标题\n\n---\n\n还有正文。\n"))
            .isNull();
    }

    /**
     * 声明了豁免的 skill 放行：改不了源文件的第三方 skill 得有个出口。
     *
     * 豁免声明写在 frontmatter 的 {@code metadata.skillmesh.spec_exempt} 里，
     * 与 CLI 侧同一套口径。
     */
    @Test
    void 声明了豁免的放行() {
        assertThat(violation("---\nmetadata:\n  skillmesh:\n    spec_exempt: true\n---\n\n正文。\n"))
            .isNull();
        assertThat(violation("---\nname: x\ndescription: y\nmetadata:\n  skillmesh:\n"
            + "    spec_exempt: true\n---\n\n   \n")).isNull();
    }

    @Test
    void 豁免写错了不算豁免() {
        assertThat(violation("---\nmetadata:\n  skillmesh:\n    spec_exempt: nope\n---\n\n正文。\n"))
            .contains("缺 name");
    }

    @Test
    void 不合规的制品扫描结论是失败() {
        byte[] zip = zip("SKILL.md", "# 没有 frontmatter\n");
        ScanResult result = new ArtifactScanner().scan(new java.io.ByteArrayInputStream(zip));

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.findings()).anyMatch(finding ->
            "不符合 agentskills.io 规范".equals(finding.rule()));
    }

    @Test
    void 制品里没有Skill文件也算不合规() {
        byte[] zip = zip("README.md", "说明\n");
        ScanResult result = new ArtifactScanner().scan(new java.io.ByteArrayInputStream(zip));

        assertThat(result.status()).isEqualTo("failed");
    }

    private String violation(String content) {
        return SkillSpecValidator.violation(content.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] zip(String name, String content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }
}
