package com.skillmesh.api.license;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 许可证识别：认出是哪一份，不判能不能用。
 *
 * 「能不能再分发」仍然要人判断（详细设计的取舍清单）：
 * 认出是 MIT 不等于这份 Skill 就能随便转发——仓库的约定、作者的补充条款、
 * 依赖的许可证都不在这份正文里。这里只把「这份内容看起来是 MIT」记下来
 * （detected），能不能用留给 approved / restricted / incompatible 那几档。
 *
 * 识别靠正文里的特征串，不靠文件名：同一个 MIT 许可证，有人叫 LICENSE，
 * 有人叫 COPYING.txt。
 */
public final class LicenseDetector {

    /** 常见的许可证文件名。按惯例排，找到第一个有内容的就用它。 */
    private static final List<String> LICENSE_FILES = List.of(
        "LICENSE", "LICENSE.md", "LICENSE.txt", "LICENCE", "LICENCE.md", "COPYING", "COPYING.md");

    /**
     * 特征串与 SPDX 标识。
     *
     * 顺序即优先级：Apache 的许可里也会提到 MIT-compatible，具体的排前面。
     * BSD-3-Clause 的特征比 BSD-2-Clause 长，先认长的那个。
     */
    private static final List<Rule> RULES = List.of(
        new Rule("AGPL-3.0", Pattern.compile("GNU AFFERO GENERAL PUBLIC LICENSE\\s+Version 3")),
        new Rule("LGPL-3.0", Pattern.compile("GNU LESSER GENERAL PUBLIC LICENSE\\s+Version 3")),
        new Rule("GPL-3.0", Pattern.compile("GNU GENERAL PUBLIC LICENSE\\s+Version 3")),
        new Rule("GPL-2.0", Pattern.compile("GNU GENERAL PUBLIC LICENSE\\s+Version 2")),
        new Rule("Apache-2.0", Pattern.compile("Apache License[\\s\\S]{0,80}Version 2\\.0")),
        new Rule("MPL-2.0", Pattern.compile("Mozilla Public License[\\s\\S]{0,40}Version 2\\.0")),
        new Rule("BSD-3-Clause", Pattern.compile(
            "(?i)redistribution and use in source and binary forms[\\s\\S]{0,400}neither the name")),
        new Rule("BSD-2-Clause", Pattern.compile(
            "(?i)redistribution and use in source and binary forms")),
        new Rule("Unlicense", Pattern.compile(
            "This is free and unencumbered software released into the public domain")),
        new Rule("CC0-1.0", Pattern.compile("CC0 1\\.0 Universal")),
        new Rule("MIT", Pattern.compile("(?i)Permission is hereby granted, free of charge")));

    /** 许可证正文最多看前面这么多字符：特征串都在开头。 */
    private static final int MAX_TEXT = 8192;

    /** 一条识别规则：标识与它的特征串。 */
    private record Rule(String id, Pattern regex) {}

    /**
     * 从正文里认出 SPDX 标识；认不出来返回 null。
     */
    public static String detect(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String head = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
        for (Rule rule : RULES) {
            if (rule.regex().matcher(head).find()) {
                return rule.id();
            }
        }
        return null;
    }

    /**
     * 在目录里按惯例文件名找许可证文件并识别。
     *
     * 只看这个目录本身：许可证在仓库根上时，调用方把根目录也传进来。
     */
    public static String detectInDirectory(Path dir) {
        for (String name : LICENSE_FILES) {
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String detected = detect(Files.readString(file, StandardCharsets.UTF_8));
                if (detected != null) {
                    return detected;
                }
            } catch (IOException e) {
                // 读不出来就当没有：识别是尽力而为的事，不该因此报错。
            }
        }
        return null;
    }

    /**
     * 在归档（zip）里按惯例文件名找许可证文件并识别。
     *
     * 文件名只看最后一段：Skill 打包时 SKILL.md 放在根目录，
     * 许可证多半也在根上，但子目录里也可能有。
     */
    public static String detectInZip(byte[] zip) {
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                int slash = name.lastIndexOf('/');
                String base = slash < 0 ? name : name.substring(slash + 1);
                if (!LICENSE_FILES.contains(base)) {
                    continue;
                }
                String detected = detect(readText(in));
                if (detected != null) {
                    return detected;
                }
            }
        } catch (IOException e) {
            // 归档读不出来当没识别出来：识别失败不该让上传失败。
            return null;
        }
        return null;
    }

    /** 读一段文本，最多读 MAX_TEXT 个字符。 */
    private static String readText(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int total = 0;
        int read;
        while (total < MAX_TEXT && (read = in.read(chunk, 0, Math.min(chunk.length, MAX_TEXT - total))) > 0) {
            buffer.write(chunk, 0, read);
            total += read;
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private LicenseDetector() {}
}
