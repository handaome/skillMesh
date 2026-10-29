package com.skillmesh.api.artifact;

import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把 Skill 目录打成制品 zip。
 *
 * 规则与 CLI 的 packager 一致：跳过隐藏文件、忽略目录、凭据类文件和
 * .skillmeshignore 命中的。发出去的文件、算摘要的文件、扫密钥的文件
 * 必须是同一批——不然审核页列的文件和发布方看到的 diff 对不上，
 * 服务端还能扫到发布方没扫过的凭据。
 */
public final class SkillPackager {

    /** 排除规则文件名（设计文档 §6.1）。 */
    private static final String IGNORE_FILE = ".skillmeshignore";

    /** 噪音目录：版本库、依赖目录、编译缓存、虚拟环境。 */
    private static final Set<String> IGNORED_DIRS =
        Set.of("node_modules", "__pycache__", ".venv");

    /** 凭据类文件名，永远不进包也不进摘要。 */
    private static final Set<String> SENSITIVE_FILES = Set.of(
        ".env", ".env.local", ".env.production", ".netrc", ".npmrc", ".pypirc",
        ".git-credentials", "credentials.json", "credentials.yaml",
        "service-account.json", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519");

    /** 凭据类扩展名。 */
    private static final Set<String> SENSITIVE_SUFFIXES = Set.of(
        ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".asc", ".gpg");

    private SkillPackager() {}

    /**
     * 打包 dir 下的 Skill。SKILL.md 放在包根，装的时候一步就能定位到 Skill 根。
     *
     * 目录里没有可打包的文件时报错——空的包装出来也用不了。
     */
    public static byte[] pack(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "Skill 目录不存在");
        }
        List<String> patterns = readIgnorePatterns(dir);
        List<Path> files = collectFiles(dir, patterns);
        if (files.isEmpty()) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "目录里没有可打包的文件: " + dir);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Path file : files) {
                ZipEntry entry = new ZipEntry(relative(dir, file));
                // 固定时间戳：同样的内容打出同样的字节，云端才认得出「没变」。
                entry.setTime(0L);
                zos.putNextEntry(entry);
                zos.write(Files.readAllBytes(file));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** 收集要进包的文件，按路径排序。 */
    private static List<Path> collectFiles(Path dir, List<String> patterns) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(path -> !path.equals(dir))
                .filter(path -> !Files.isDirectory(path))
                // 符号链接不打包：它可能指向包外的文件，跟着进来就是路径穿越。
                .filter(path -> !Files.isSymbolicLink(path))
                .filter(path -> Files.isRegularFile(path))
                .filter(path -> !underSkippedDir(dir, path))
                .filter(path -> !isSensitive(path.getFileName().toString()))
                .filter(path -> !matchesIgnore(patterns, relative(dir, path)))
                .sorted(Comparator.comparing(path -> relative(dir, path)))
                .forEach(files::add);
        }
        return files;
    }

    /**
     * 路径上有没有要整段跳过的目录。
     *
     * 隐藏目录也算：.git、.idea 这类整个跳过。
     */
    private static boolean underSkippedDir(Path dir, Path file) {
        for (Path part : dir.relativize(file)) {
            String name = part.toString();
            if (name.startsWith(".") || IGNORED_DIRS.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /** 凭据类文件：清单里的名字、.env.* 动态命名、凭据类扩展名。 */
    private static boolean isSensitive(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (SENSITIVE_FILES.contains(lower) || lower.startsWith(".env.")) {
            return true;
        }
        for (String suffix : SENSITIVE_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** 读目录下的 .skillmeshignore。没有这个文件就是空规则，不算错误。 */
    private static List<String> readIgnorePatterns(Path dir) throws IOException {
        List<String> patterns = new ArrayList<>();
        Path file = dir.resolve(IGNORE_FILE);
        if (!Files.isRegularFile(file)) {
            return patterns;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            patterns.add(trimmed);
        }
        return patterns;
    }

    /**
     * 相对路径是否被 .skillmeshignore 排除。
     *
     * 规则是 gitignore 的子集：支持 / 开头锚定到根、结尾 / 只管目录、
     * * 通配与前缀匹配；不支持 ! 取反和 ** 跨目录。
     */
    static boolean matchesIgnore(List<String> patterns, String relative) {
        for (String pattern : patterns) {
            if (matchesPattern(pattern, relative)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPattern(String rawPattern, String target) {
        String pattern = rawPattern.endsWith("/")
            ? rawPattern.substring(0, rawPattern.length() - 1) : rawPattern;
        boolean anchored = pattern.startsWith("/");
        if (anchored) {
            pattern = pattern.substring(1);
        }
        if (pattern.isEmpty()) {
            return false;
        }
        if (!anchored) {
            // 不锚定：任意一层目录里命中都算。
            if (matchesGlob(pattern, baseName(target))) {
                return true;
            }
            for (String segment : target.split("/")) {
                if (matchesGlob(pattern, segment)) {
                    return true;
                }
            }
            // 前缀匹配：dist/ 这种写法要能盖住整个目录。
            return target.startsWith(pattern + "/");
        }
        return matchesGlob(pattern, target) || target.startsWith(pattern + "/");
    }

    private static boolean matchesGlob(String pattern, String text) {
        return java.nio.file.FileSystems.getDefault()
            .getPathMatcher("glob:" + pattern).matches(Path.of(text.replace('/', java.io.File.separatorChar)));
    }

    private static String baseName(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? path : path.substring(index + 1);
    }

    /** 包内路径：相对目录、正斜杠。 */
    private static String relative(Path dir, Path file) {
        return dir.relativize(file).toString().replace('\\', '/');
    }
}
