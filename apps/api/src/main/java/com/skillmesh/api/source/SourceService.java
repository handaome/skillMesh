package com.skillmesh.api.source;

import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.common.SafePath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
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
    private final Semaphore slots;
    private final String proxy;

    public SourceService(GitHostPolicy policy,
                         @Value("${skillmesh.git.timeout-seconds:30}") long timeoutSeconds,
                         @Value("${skillmesh.git.max-candidates:100}") int maxCandidates,
                         @Value("${skillmesh.git.max-concurrent:4}") int maxConcurrent,
                         @Value("${skillmesh.git.proxy:}") String proxy) {
        this.policy = policy;
        this.timeoutSeconds = timeoutSeconds;
        this.maxCandidates = maxCandidates;
        // 并发上限（设计文档 §14）：拉仓库是重活，几个人同时点就能把机器和带宽打满。
        this.slots = new Semaphore(Math.max(1, maxConcurrent));
        this.proxy = proxy == null ? "" : proxy.trim();
    }

    /** 解析仓库，返回其中的 Skill 候选清单。 */
    public ResolveResult resolve(String repositoryUrl, String subpath, String ref) {
        GitHostPolicy.Allowed allowed = policy.allow(repositoryUrl);
        try (Checkout checkout = checkout(allowed, ref)) {
            return new ResolveResult(repositoryUrl, checkout.commit(),
                candidates(checkout.root(), subpath));
        } catch (IOException e) {
            throw new ApiException(502, ErrorCodes.INTERNAL,
                "读取检出内容失败：" + e.getMessage());
        }
    }

    /**
     * 把仓库浅克隆到临时目录。
     *
     * 用 fetch 而不是 clone --branch：分支、标签、commit 三种 ref 都能取，
     * 而 clone --branch 不接 commit。
     *
     * 临时目录归调用方管（try-with-resources 关掉即删）。
     */
    public Checkout checkout(String repositoryUrl, String ref) {
        return checkout(policy.allow(repositoryUrl), ref);
    }

    private Checkout checkout(GitHostPolicy.Allowed allowed, String ref) {
        acquireSlot();
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("skillmesh-source-");
            // 仓库直接建在工作区里：root 就是仓库根，扫描与定位子目录都对着它。
            String commit = clone(allowed, ref, workspace);
            return new Checkout(workspace, commit);
        } catch (ApiException e) {
            deleteQuietly(workspace);
            throw e;
        } catch (Exception e) {
            deleteQuietly(workspace);
            throw new ApiException(502, ErrorCodes.INTERNAL,
                "拉取仓库失败：" + e.getMessage());
        } finally {
            slots.release();
        }
    }

    private void acquireSlot() {
        try {
            // 拿不到位就干等一会儿：宁可排队也别把请求全拒了
            if (!slots.tryAcquire(5, TimeUnit.SECONDS)) {
                throw new ApiException(503, ErrorCodes.INTERNAL,
                    "Git 解析任务排队超时，稍后再试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, ErrorCodes.INTERNAL, "Git 解析被中断");
        }
    }

    /** 查远端某个 ref 指向的 commit，不带完整内容。 */
    public UpdateCheck checkUpdates(String repositoryUrl, String ref) {
        GitHostPolicy.Allowed allowed = policy.allow(repositoryUrl);
        try {
            String head = run(List.of("git", "ls-remote", allowed.url(),
                ref == null || ref.isBlank() ? "HEAD" : ref), pin(allowed));
            String commit = head.isBlank() ? "" : head.split("\\s+")[0];
            return new UpdateCheck(commit, repositoryUrl, ref == null ? "" : ref);
        } catch (IOException e) {
            throw new ApiException(502, ErrorCodes.INTERNAL, "查询远端失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(504, ErrorCodes.INTERNAL, "查询远端超时");
        }
    }

    private String clone(GitHostPolicy.Allowed allowed, String ref, Path target)
            throws IOException, InterruptedException {
        String url = allowed.url();
        run(List.of("git", "init", "--quiet", target.toString()));
        // 关掉换行自动转换：Skill 里常带 shell 脚本，转成 CRLF 到了 Unix 上就跑不起来。
        run(List.of("git", "-C", target.toString(), "config", "core.autocrlf", "false"));
        run(List.of("git", "-C", target.toString(), "remote", "add", "origin", url));
        List<String> fetch = new ArrayList<>(List.of(
            "git", "-C", target.toString(), "fetch", "--depth", "1", "--quiet", "origin"));
        fetch.add(ref == null || ref.isBlank() ? "HEAD" : ref);
        run(fetch, pin(allowed));
        run(List.of("git", "-C", target.toString(), "checkout", "--quiet", "FETCH_HEAD"));
        return run(List.of("git", "-C", target.toString(), "rev-parse", "HEAD"));
    }

    /**
     * 这个 ref 是分支、标签还是 commit。
     *
     * git fetch 写下的 FETCH_HEAD 第二列就是它（branch 'main' / tag 'v1.2.0'），
     * 读一下比自己猜准。猜错只影响来源元数据里 ref_type 这一栏。
     */
    public String refType(Path repo, String ref) {
        if (ref == null || ref.isBlank()) {
            return "default";
        }
        try {
            Path fetchHead = repo.resolve(".git").resolve("FETCH_HEAD");
            if (Files.exists(fetchHead)) {
                String head = Files.readAllLines(fetchHead, StandardCharsets.UTF_8)
                    .stream().findFirst().orElse("");
                if (head.contains("tag '")) {
                    return "tag";
                }
                if (head.contains("branch '")) {
                    return "branch";
                }
            }
        } catch (IOException ignored) {
            // 读不到就按下面的样子猜
        }
        return ref.length() == 40 ? "commit" : "branch";
    }

    /** 在检出里找含 SKILL.md 的目录，按相对路径排序，和 CLI 的扫描口径一致。 */
    private List<Candidate> candidates(Path root, String subpath) throws IOException {
        List<Candidate> found = new ArrayList<>();
        Path scanRoot = root;
        if (subpath != null && !subpath.isBlank()) {
            // 越界路径与绝对路径一律拒。目录名里带 ".." 的（my..dir）是合法目录，
            // 不能把 ".." 子串删掉再拼——那会把目录名改掉，扫到别的目录上去。
            if (SafePath.escapes(subpath)) {
                throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "子目录越界");
            }
            scanRoot = root.resolve(subpath).normalize();
            if (!scanRoot.startsWith(root)) {
                throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "子目录越界");
            }
            // 指名的子目录自己就是 Skill 时，候选就是它一个。
            // 导入与更新那边（locate）也是这个口径：路径指的是 Skill 目录本身。
            // 这里若还往下扫，用户「选了 skills/other」会得到一份空清单。
            if (Files.exists(scanRoot.resolve("SKILL.md"))) {
                found.add(new Candidate(root.relativize(scanRoot).toString().replace('\\', '/'),
                    scanRoot.getFileName().toString()));
                return found;
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

    /**
     * git 命令一律关掉 HTTP 重定向跟随。
     *
     * 主机白名单和 DNS 校验只管得住初始 URL；git 默认的 followRedirects=initial
     * 会跟着这次重定向走，于是 github.com 302 到 169.254.169.254 是能打通的。
     * 关掉之后重定向直接失败，用户改用最终地址就行。
     *
     * 顺带把地址固定也挂上去：-c 的选项要排在子命令前面。
     */
    private static List<String> withSafeRedirects(List<String> command, Pin pin) {
        if (command.isEmpty() || !"git".equals(command.get(0))) {
            return command;
        }
        List<String> prefix = new ArrayList<>(List.of("git", "-c", "http.followRedirects=false"));
        if (pin != null) {
            for (String option : pin.options()) {
                prefix.add("-c");
                prefix.add(option);
            }
        }
        prefix.addAll(command.subList(1, command.size()));
        return prefix;
    }

    /**
     * 一次 git 操作的地址固定：命令行选项与 ssh 命令。
     *
     * @param options git 的 -c 选项（http.curloptResolve）
     * @param sshCommand ssh 命令（ssh:// 时用）
     */
    record Pin(List<String> options, String sshCommand) {}

    /**
     * 把 git 连的地址固定到刚校验过的那个。
     *
     * 校验与 git 自己的解析之间有时间差：这段时间里 DNS 换成内网地址，
     * 校验就白做了（DNS rebinding）。
     *
     * https 用 curl 的 resolve 把主机钉在这些地址上；ssh 用 HostName 指到
     * 那个地址，再用 HostKeyAlias 保住主机名——主机密钥照旧按域名校验，
     * 不会因为换了地址就跳过校验。
     */
    Pin pin(GitHostPolicy.Allowed allowed) {
        String scheme = allowed.url().startsWith("ssh://") ? "ssh" : "https";
        if ("ssh".equals(scheme)) {
            return new Pin(List.of(),
                "ssh -o HostName=" + allowed.addresses().get(0)
                    + " -o HostKeyAlias=" + allowed.host());
        }
        int port = 443;
        try {
            int parsed = URI.create(allowed.url()).getPort();
            if (parsed > 0) {
                port = parsed;
            }
        } catch (IllegalArgumentException ignored) {
            // 地址已经校验过了，解析不出端口就用默认的。
        }
        StringBuilder resolve = new StringBuilder();
        for (String address : allowed.addresses()) {
            if (resolve.length() > 0) {
                resolve.append(',');
            }
            resolve.append(allowed.host()).append(':').append(port).append(':').append(address);
        }
        return new Pin(List.of("http.curloptResolve=" + resolve), "");
    }

    String run(List<String> command) throws IOException, InterruptedException {
        return run(command, null);
    }

    String run(List<String> command, Pin pin) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(withSafeRedirects(command, pin));
        builder.redirectErrorStream(true);
        // ssh:// 的地址固定走环境变量：git 调 ssh 时才用得上。
        if (pin != null && !pin.sshCommand().isBlank()) {
            builder.environment().put("GIT_SSH_COMMAND", pin.sshCommand());
        }
        // 显式配了代理就写进 git 的环境。系统环境里的 HTTPS_PROXY 本身就会继承，
        // 不配这个项也能走代理——这个项是给「代理只配在服务里」的场景用的。
        if (!proxy.isBlank()) {
            builder.environment().put("HTTPS_PROXY", proxy);
            builder.environment().put("HTTP_PROXY", proxy);
        }
        Process process = builder.start();
        // 输出流单独一个线程读。顺序不能反：先 readAllBytes 再 waitFor 的话，
        // git 卡在网络上时 readAllBytes 会一直等流关闭，超时上限形同虚设。
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (var in = process.getInputStream()) {
                in.transferTo(buffer);
            } catch (IOException ignored) {
                // 进程被强杀时读流会失败；输出不完整由超时分支和退出码兜底
            }
        });
        drain.setDaemon(true);
        drain.start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("git 命令超时（" + timeoutSeconds + "s）");
        }
        drain.join(TimeUnit.SECONDS.toMillis(1));
        byte[] output = buffer.toByteArray();
        String text = new String(output, StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IOException(text.isEmpty() ? "git 命令失败" : text);
        }
        return text;
    }

    private static void deleteQuietly(Path path) {
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

    /**
     * 一次检出。root 是仓库根目录，commit 是实际解析到的提交。
     *
     * 临时目录用完就删：try-with-resources 关掉即删。
     */
    public record Checkout(Path root, String commit) implements AutoCloseable {
        @Override
        public void close() {
            SourceService.deleteQuietly(root);
        }
    }
}
