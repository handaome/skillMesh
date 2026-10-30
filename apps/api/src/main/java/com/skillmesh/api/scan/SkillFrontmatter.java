package com.skillmesh.api.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SKILL.md 的 frontmatter 怎么读成 Skill 的元信息。
 *
 * 与 CLI 侧 {@code skillscan.Metadata} 同一套口径：name 与 description 是
 * Skill 自己的列，其余（license / author / platforms / tags / metadata.*）
 * 是扩展元信息，随 Skill 存进云端——别的地方装的时候能读到，
 * 网页端的「规范元信息」也才有东西可显示。
 */
public final class SkillFrontmatter {

    /** 服务端最多收 30 个标签，每个 64 字符。多了会被请求校验挡下。 */
    private static final int MAX_TAGS = 30;
    private static final int MAX_TAG_LEN = 64;

    private SkillFrontmatter() {
    }

    /** 读一个 Skill 目录的 frontmatter。读不到就当没有。 */
    public static Map<?, ?> read(Path skillDir) {
        Path file = skillDir.resolve(SkillSpecValidator.FILE_NAME);
        try {
            if (!Files.exists(file)) {
                return Map.of();
            }
            return SkillSpecValidator.frontmatter(Files.readAllBytes(file));
        } catch (IOException e) {
            return Map.of();
        }
    }

    /** 取一个字符串字段。没有或不是字符串就返回空串。 */
    public static String text(Map<?, ?> meta, String key) {
        Object value = meta.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * frontmatter 里的扩展元信息：license、author、platforms、tags、metadata。
     *
     * name 与 description 不算扩展字段：它们已经是 Skill 自己的列，
     * 再塞一遍两处会对不上。
     */
    public static Map<String, Object> extension(Map<?, ?> meta) {
        Map<String, Object> extra = new LinkedHashMap<>();
        putText(extra, "license", meta.get("license"));
        putText(extra, "author", meta.get("author"));
        putList(extra, "platforms", meta.get("platforms"));
        putList(extra, "tags", meta.get("tags"));
        if (meta.get("metadata") instanceof Map<?, ?> nested) {
            extra.put("metadata", nested);
        }
        return extra;
    }

    /**
     * frontmatter 里的标签。
     *
     * 多写的、没用的、超长的都去掉：不去掉整个导入会被 400 挡下，
     * 报错还不会说是哪个标签的问题。
     */
    public static List<String> tags(Map<?, ?> meta) {
        List<String> out = new ArrayList<>();
        if (!(meta.get("tags") instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            String tag = item == null ? "" : String.valueOf(item).trim();
            if (tag.isEmpty() || tag.length() > MAX_TAG_LEN) {
                continue;
            }
            out.add(tag);
            if (out.size() == MAX_TAGS) {
                break;
            }
        }
        return out;
    }

    private static void putText(Map<String, Object> into, String key, Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (!text.isEmpty()) {
            into.put(key, text);
        }
    }

    private static void putList(Map<String, Object> into, String key, Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        List<Object> items = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                items.add(String.valueOf(item).trim());
            }
        }
        if (!items.isEmpty()) {
            into.put(key, items);
        }
    }
}
