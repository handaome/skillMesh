package com.skillmesh.api.source;

import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 服务端解析 Git 来源（设计文档 §12 的 sources/resolve 与 sources/check-updates）。
 *
 * 前提是 {@link GitHostPolicy} 先放行——这里才会真的去执行 git。
 * 拉仓库是有代价的操作，所以有超时、有浅克隆、有一次性的工作目录。
 */
@Service
public class SourceService {

    private static final int MAX_SCAN_DEPTH = 4;

    private final GitHostPolicy policy;
    private final long timeoutSeconds;
    private final int maxCandidates;

    public SourceService(GitHostPolicy policy,
                         @Value("${skillmesh.git.timeout-seconds:30}") long timeoutSeconds,
                         @Value("${skillmesh.git.max-candidates:100}") int maxCandidates) {
        this.policy = policy;
        this.timeoutSeconds = timeoutSeconds;
        this.maxCandidates = maxCandidates;
    }

    /** 解析仓库，返回其中的 Skill 候选清单。 */
    public ResolveResult resolve(String repositoryUrl, String subpath, String ref) {
        String url = policy.requireAllowed(repositoryUrl);
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("skillmesh-source-");
            String head = clone(url, ref, workspace.resolve("repo"));
            return new ResolveResult(repositoryUrl, head, candidates(workspace.resolve("repo"), subpath));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, ErrorCodes.INTERNAL,
                "拉取仓库失败：" + e.getMessage());
        } finally {
            deleteQuietly(workspace);
        }
    }

    /** 查远端某个 ref 指向的 commit，不带完整内容。 */
    public UpdateCheck checkUpdates(String repositoryUrl, String ref) {
        String url = policy.requireAllowed(repositoryUrl);
        try {
            String head = run(List.of("git", "ls-remote", url, ref == null || ref.isBlank() ? "HEAD" : ref));
            String commit = head.isBlank() ? "" : head.split("\\s+")[0];
            return new UpdateCheck(commit, repositoryUrl, ref == null ? "" : ref);
        } catch (IOException e) {
            throw new ApiException(502, ErrorCodes.INTERNAL, "查询远端失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(504, ErrorCodes.INTERNAL, "查询远端超时");
        }
    }

    private String clone(String url, String ref, Path target) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
            "git", "clone", "--depth", "1", "--quiet"));
        if (ref != null && !ref.isBlank()) {
            command.add("--branch");
            command.add(ref);
        }
        command.add(url);
        command.add(target.toString());
        run(command);
        return run(List.of("git", "-C", target.toString(), "rev-parse", "HEAD"));
    }

    /** 在检出里找含 SKILL.md 的目录，按相对路径排序，和 CLI 的扫描口径一致。 */
    private List<Candidate> candidates(Path root, String subpath) throws IOException {
        List<Candidate> found = new ArrayList<>();
        Path scanRoot = root;
        if (subpath != null && !subpath.isBlank()) {
            scanRoot = root.resolve(subpath.replace("..", "")).normalize();
            if (!scanRoot.startsWith(root)) {
                throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "子目录越界");
            }
        }
        final Path base = scanRoot;
        try (var stream = Files.walk(base, MAX_SCAN_DEPTH)) {
            stream.filter(Files::isDirectory)
                .filter(dir -> !dir.equals(base) && Files.exists(dir.resolve("SKILL.md")))
                .sorted(Comparator.comparing(Path::toString))
                .limit(maxCandidates)
                .forEach(dir -> {
                    String path = root.relativize(dir).toString().replace('\\', '/');
                    found.add(new Candidate(path, dir.getFileName().toString()));
                });
        }
        return found;
    }

    private String run(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("git 命令超时（" + timeoutSeconds + "s）");
        }
        String text = new String(output, StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IOException(text.isEmpty() ? "git 命令失败" : text);
        }
        return text;
    }

    private void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException ignored) {
                    // 临时目录删不掉不影响主流程
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    /** 解析结果。 */
    public record ResolveResult(String repositoryUrl, String headCommit,
                                List<Candidate> candidates) {}

    public record Candidate(String path, String name) {}

    public record UpdateCheck(String commit, String repositoryUrl, String ref) {}
}
