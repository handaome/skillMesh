package com.skillmesh.api.scan;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * agentskills.io 规范校验。
 *
 * 规范就那么几条硬要求（Hermes 这类按规范扫的 Agent 直接照它判）：
 * SKILL.md 以 {@code ---} 开头，frontmatter 是 YAML 映射，
 * {@code name} 与 {@code description} 必填且有长度上限，正文非空，
 * 整个文件不超过十万字符。
 *
 * 与 CLI 的 {@code skillscan.ValidateSpec} 是同一套规则。两边都要有：
 * 绕过 CLI 直接调 API 上传，同样得守住这道闸。
 */
public final class SkillSpecValidator {

    /** 描述一个 Skill 的文件名。 */
    public static final String FILE_NAME = "SKILL.md";

    /** name 的字符数上限。 */
    public static final int MAX_NAME_LEN = 64;
    /** description 的字符数上限。 */
    public static final int MAX_DESCRIPTION_LEN = 1024;
    /** 整个 SKILL.md 的字符数上限。 */
    public static final int MAX_FILE_CHARS = 100000;

    private SkillSpecValidator() {
    }

    /**
     * 不合规的原因。返回 null 表示合规。
     *
     * 返回人话而不是字段名：用户看到的是「缺 description」，不是「description 字段为空」。
     */
    public static String violation(byte[] content) {
        Parsed parsed = parse(content);
        if (parsed.error() != null) {
            return parsed.error();
        }
        String name = text(parsed.meta().get("name"));
        if (name.isEmpty()) {
            return "SKILL.md 的 frontmatter 缺 name";
        }
        int nameLen = name.codePointCount(0, name.length());
        if (nameLen > MAX_NAME_LEN) {
            return "SKILL.md 的 name 有 " + nameLen + " 个字符，超过上限 " + MAX_NAME_LEN;
        }
        String description = text(parsed.meta().get("description"));
        if (description.isEmpty()) {
            return "SKILL.md 的 frontmatter 缺 description";
        }
        int descLen = description.codePointCount(0, description.length());
        if (descLen > MAX_DESCRIPTION_LEN) {
            return "SKILL.md 的 description 有 " + descLen + " 个字符，超过上限 "
                + MAX_DESCRIPTION_LEN;
        }
        return null;
    }

    /**
     * frontmatter 里的字段。解析不出返回空 Map。
     *
     * 导入别处的 skill 时要用它填名字与描述：那是这个 skill 自己声明的，
     * 比我们从目录名猜的准。
     */
    public static Map<?, ?> frontmatter(byte[] content) {
        Parsed parsed = parse(content);
        return parsed.meta() == null ? Map.of() : parsed.meta();
    }

    /** 解析结果：出错时 error 有话，meta 与 body 为空。 */
    private record Parsed(Map<?, ?> meta, String body, String error) {
    }

    private static Parsed parse(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8).replace("\r\n", "\n");
        int chars = text.codePointCount(0, text.length());
        if (chars > MAX_FILE_CHARS) {
            return new Parsed(null, null,
                "SKILL.md 共 " + chars + " 个字符，超过上限 " + MAX_FILE_CHARS);
        }
        if (!text.startsWith("---\n")) {
            return new Parsed(null, null, "SKILL.md 开头必须是 --- 包裹的 YAML frontmatter");
        }
        int end = text.indexOf("\n---", 4);
        if (end < 0) {
            return new Parsed(null, null, "SKILL.md 的 frontmatter 没有闭合");
        }
        String body = text.substring(end + 4);
        if (body.startsWith("\n")) {
            body = body.substring(1);
        }
        if (body.isBlank()) {
            return new Parsed(null, null, "SKILL.md 只有 frontmatter，没有正文");
        }
        Map<?, ?> meta;
        try {
            Object parsed = new Yaml().load(text.substring(4, end));
            if (!(parsed instanceof Map<?, ?> map)) {
                return new Parsed(null, null, "SKILL.md 的 frontmatter 不是 YAML 映射");
            }
            meta = map;
        } catch (RuntimeException e) {
            return new Parsed(null, null, "SKILL.md 的 frontmatter 不是合法 YAML");
        }
        return new Parsed(meta, body, null);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
