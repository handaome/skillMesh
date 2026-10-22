package com.skillmesh.api.scan;

import com.skillmesh.api.scan.ScanRule.Severity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

/**
 * 服务端制品扫描（详细设计 §10 内容扫描）。
 *
 * 客户端也会扫一遍，但服务端这份才是准的——绕过 CLI 的上传路径同样要过。
 */
@Component
public class ArtifactScanner {

    /** 扫描器标识，写进 skill_scans.scanner_name / scanner_version。 */
    public static final String NAME = "skillmesh-server-scan";
    public static final String VERSION = "0.1.0";

    /** 单文件与总量上限，与 CLI 的 skillscan.DefaultLimits 对齐。 */
    private static final long MAX_FILE_BYTES = 20L << 20;
    private static final long MAX_TOTAL_BYTES = 50L << 20;
    private static final int MAX_FILES = 5000;

    /** 路径穿越这条规则的名字。 */
    private static final String RULE_PATH_TRAVERSAL = "路径穿越";

    /** 超过这个时长按「未完成」处理，不让它拖住上传流程。 */
    private static final long TIMEOUT_MS = 30_000;

    /**
     * 规则清单：只收误报极低的特征。
     * 文档里的占位符不该被拦住，真密钥的特征都很固定。
     */
    private static final List<ScanRule> RULES = List.of(
        new ScanRule("AWS Access Key", Pattern.compile("AKIA[0-9A-Z]{16}"), Severity.BLOCKING),
        new ScanRule("Google API Key", Pattern.compile("AIza[0-9A-Za-z_-]{35}"), Severity.BLOCKING),
        new ScanRule("GitHub Token", Pattern.compile("gh[pousr]_[A-Za-z0-9]{36,}"), Severity.BLOCKING),
        new ScanRule("OpenAI Key", Pattern.compile("sk-[A-Za-z0-9]{20,}"), Severity.BLOCKING),
        new ScanRule("Slack Token", Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"), Severity.BLOCKING),
        new ScanRule("私钥文件内容",
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"), Severity.BLOCKING));

    /** 二进制文件超过这个大小就不扫了——凭据不会藏在那里面。 */
    private static final int MAX_SCAN_BYTES = 2 << 20;

    /**
     * 可疑内容规则（设计文档 §10：脚本、可疑命令、外部下载、依赖信息）。
     * 和密钥规则不同，这些只提示不阻断——脚本里有 curl 不等于它有害，
     * 但装之前应该让人看见。
     */
    private static final List<ScanRule> RISK_RULES = List.of(
        new ScanRule("下载后直接执行", Pattern.compile(
            "(?i)(curl|wget|iwr|invoke-webrequest)[^|;&\\n]*\\|\\s*(sudo\\s+)?(ba)?sh|iex\\s*\\("),
            Severity.WARNING),
        new ScanRule("编码后解码执行", Pattern.compile(
            "(?i)(base64\\s+-d|frombase64string|xxd\\s+-r)[^|;&\\n]*\\|\\s*(ba)?sh"),
            Severity.WARNING),
        new ScanRule("递归删除根目录", Pattern.compile(
            "rm\\s+(-[a-zA-Z]*[rf][a-zA-Z]*\\s+)+(/|~|\\$HOME)(\\s|$|/)"), Severity.WARNING),
        new ScanRule("动态求值", Pattern.compile("(?i)\\b(eval|exec)\\s*\\("),
            Severity.WARNING),
        new ScanRule("权限放宽到所有人", Pattern.compile("(?i)chmod\\s+(-[a-zA-Z]+\\s+)*777"),
            Severity.WARNING),
        new ScanRule("关闭安全机制", Pattern.compile(
            "(?i)(curl\\s+[^\\n]*(-k|--insecure)|set-executionpolicy\\s+bypass)"),
            Severity.WARNING),
        new ScanRule("读取凭据文件", Pattern.compile(
            "(?i)(~/\\.ssh/id_|\\.aws/credentials|\\.netrc|\\.npmrc|id_rsa)"),
            Severity.WARNING),
        new ScanRule("安装依赖", Pattern.compile(
            "(?i)(npm\\s+(install|i)\\b|pip\\s+install\\b|gem\\s+install\\b|go\\s+get\\b)"),
            Severity.WARNING),
        new ScanRule("外部下载", Pattern.compile("https?://[^\\s\"'<>)]+"), Severity.WARNING));

    /** 只有脚本和配置才扫这些规则：把每个 .md 里的链接都报一遍只会淹没真问题。 */
    private static final List<String> RISK_EXTS = List.of(
        ".sh", ".bash", ".zsh", ".ps1", ".cmd", ".bat", ".py", ".rb", ".js", ".ts");
    private static final List<String> RISK_FILES = List.of(
        "package.json", "requirements.txt", "pyproject.toml", "Gemfile");

    public ScanResult scan(InputStream artifact) {
        long started = System.currentTimeMillis();
        List<ScanResult.Finding> findings = new ArrayList<>();
        String error = readEntries(artifact, findings);
        long duration = System.currentTimeMillis() - started;

        if (error != null) {
            return new ScanResult("error", findings, error, duration);
        }
        if (duration > TIMEOUT_MS) {
            return new ScanResult("error", findings,
                "扫描超过 " + TIMEOUT_MS + "ms，按未完成处理", duration);
        }
        boolean blocking = false;
        StringBuilder summary = new StringBuilder();
        for (ScanResult.Finding finding : findings) {
            if (isBlocking(finding)) {
                blocking = true;
            }
            summary.append(finding.path()).append(':').append(finding.line())
                .append(' ').append(finding.rule()).append('\n');
        }
        if (findings.isEmpty()) {
            return new ScanResult("passed", findings, "未发现问题", duration);
        }
        return new ScanResult(blocking ? "failed" : "warning", findings,
            summary.toString().trim(), duration);
    }

    /** 解包并逐文件匹配规则。返回非 null 表示扫描本身出错（算 failed/error）。 */
    private String readEntries(InputStream artifact, List<ScanResult.Finding> findings) {
        int files = 0;
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(artifact)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                files++;
                if (files > MAX_FILES) {
                    return "文件数超过上限 " + MAX_FILES;
                }
                String path = entry.getName();
                if (path.startsWith("/") || path.contains("..")) {
                    findings.add(new ScanResult.Finding(path, 0, RULE_PATH_TRAVERSAL,
                        "制品里出现越界路径，不该发到云端"));
                    continue;
                }
                byte[] data = readEntry(zip, MAX_FILE_BYTES, path);
                total += data.length;
                if (total > MAX_TOTAL_BYTES) {
                    return "制品总大小超过上限 " + MAX_TOTAL_BYTES;
                }
                scanContent(path, data, findings);
            }
            return null;
        } catch (IOException e) {
            return "制品解包失败: " + e.getMessage();
        }
    }

    /** 脚本与配置文件才扫可疑内容规则。 */
    private boolean scansRisks(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        if (RISK_FILES.contains(name)) {
            return true;
        }
        return RISK_EXTS.stream().anyMatch(lower::endsWith);
    }

    private void scanContent(String path, byte[] data, List<ScanResult.Finding> findings) {
        if (data.length > MAX_SCAN_BYTES || isBinary(data)) {
            return;
        }
        String[] lines = new String(data, StandardCharsets.UTF_8).split("\n", -1);
        boolean scanRisks = scansRisks(path);
        for (int i = 0; i < lines.length; i++) {
            for (ScanRule rule : RULES) {
                if (rule.pattern().matcher(lines[i]).find()) {
                    findings.add(new ScanResult.Finding(path, i + 1, rule.name(),
                        "疑似真实凭据，别把它发上云端"));
                }
            }
            if (!scanRisks) {
                continue;
            }
            for (ScanRule rule : RISK_RULES) {
                if (rule.pattern().matcher(lines[i]).find()) {
                    findings.add(new ScanResult.Finding(path, i + 1, rule.name(),
                        "可疑内容，装之前自己判断"));
                }
            }
        }
    }

    private static boolean isBlocking(ScanResult.Finding finding) {
        // 路径穿越是包自己的毛病：条目名越界，装到 Agent 目录外面就写出去了。
        // 这种不能只记个 warning——它不是「疑似」，是确定会出事。
        if (RULE_PATH_TRAVERSAL.equals(finding.rule())) {
            return true;
        }
        return RULES.stream().anyMatch(rule -> rule.name().equals(finding.rule())
            && rule.blocking());
    }

    /**
     * 读一个解压项，**边读边卡上限**。
     *
     * 先读进来再看大小就晚了：一个 500MB 的解压项能把堆撑爆，
     * 那时再判断超限已经没意义。
     */
    private byte[] readEntry(ZipInputStream zip, long limit, String path) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = zip.read(buffer)) > 0) {
            if (out.size() + (long) read > limit) {
                throw new IOException("文件 " + path + " 超过单文件大小上限 " + limit + " 字节");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private boolean isBinary(byte[] data) {
        int limit = Math.min(data.length, 8192);
        for (int i = 0; i < limit; i++) {
            if (data[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
