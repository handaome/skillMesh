package com.skillmesh.api.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 真的克隆一次本地仓库。
 *
 * 之前这条链路全靠 mock，藏过一个 bug：检出的工作区里仓库在子目录，
 * 扫描与定位子目录都对着工作区去找，于是永远找不到 Skill 目录。
 * 拿真 git 跑一遍才看得见。
 */
@SpringBootTest
class SourceServiceCloneTest {

    @Autowired
    private SourceService sources;

    /** 放行本地 file:// 地址——策略只认 https/ssh，测试不走公网。 */
    @MockitoBean
    private GitHostPolicy policy;

    /** 放行本地地址：策略只认 https/ssh，测试不走公网。 */
    private void allowAnyUrl() {
        when(policy.allow(anyString())).thenAnswer(call -> {
            String url = call.getArgument(0);
            return new GitHostPolicy.Allowed(url, "localhost", List.of("127.0.0.1"));
        });
    }

    @Test
    void 浅克隆后能找到仓库里的Skill目录() throws Exception {
        assumeTrue(gitAvailable(), "本机没有 git，跳过");
        String url = localBareRepo();
        allowAnyUrl();

        SourceService.ResolveResult result = sources.resolve(url, null, null);

        assertThat(result.headCommit()).isNotBlank();
        assertThat(result.candidates()).extracting(SourceService.Candidate::path)
            .contains("skills/probe");
    }

    @Test
    void 仓库里有多个Skill目录时列出候选并能按路径选一个() throws Exception {
        assumeTrue(gitAvailable(), "本机没有 git，跳过");
        String url = localBareRepo();
        allowAnyUrl();

        // 候选清单：两个 Skill 目录都要列出来，用户自己挑。
        SourceService.ResolveResult all = sources.resolve(url, null, null);
        assertThat(all.candidates()).extracting(SourceService.Candidate::path)
            .contains("skills/probe", "skills/other");

        // 用户明确选了其中一个：只看那个子目录下的 Skill。
        SourceService.ResolveResult picked = sources.resolve(url, "skills/other", null);
        assertThat(picked.candidates()).extracting(SourceService.Candidate::path)
            .containsExactly("skills/other");
    }

    @Test
    void 检出目录本身就是仓库根() throws Exception {
        assumeTrue(gitAvailable(), "本机没有 git，跳过");
        String url = localBareRepo();
        allowAnyUrl();

        try (SourceService.Checkout checkout = sources.checkout(url, "HEAD")) {
            // 定位 Skill 目录按的是检出的根：根下必须直接能看见 skills/probe。
            assertThat(Files.exists(checkout.root().resolve("skills/probe/SKILL.md"))).isTrue();
        }
    }

    @Test
    void 标签要认成标签而不是分支() throws Exception {
        assumeTrue(gitAvailable(), "本机没有 git，跳过");
        String url = localBareRepo();
        allowAnyUrl();

        try (SourceService.Checkout checkout = sources.checkout(url, "v1.0.0")) {
            assertThat(sources.refType(checkout.root(), "v1.0.0")).isEqualTo("tag");
        }
    }

    /** 造一个带 SKILL.md 的本地裸仓库，返回它的 file:// 地址。 */
    private String localBareRepo() throws Exception {        Path work = Files.createTempDirectory("skillmesh-git-fixture-");
        Path src = work.resolve("src");
        git("init", "--quiet", src.toString());
        Files.createDirectories(src.resolve("skills/probe"));
        Files.writeString(src.resolve("skills/probe/SKILL.md"), "# probe\n",
            StandardCharsets.UTF_8);
        // 第二个 Skill 目录：仓库里有几个就要列几个，用户自己挑。
        Files.createDirectories(src.resolve("skills/other"));
        Files.writeString(src.resolve("skills/other/SKILL.md"), "# other\n",
            StandardCharsets.UTF_8);
        git("-C", src.toString(), "add", "-A");
        git("-C", src.toString(), "-c", "user.email=probe@example.com",
            "-c", "user.name=probe", "commit", "--quiet", "-m", "init");
        Path bare = work.resolve("bare.git");
        git("clone", "--quiet", "--bare", src.toString(), bare.toString());
        git("-C", bare.toString(), "tag", "v1.0.0");
        return bare.toUri().toString();
    }

    private void git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("git 失败：" + String.join(" ", command)
                + " → " + output);
        }
    }

    private boolean gitAvailable() {
        try {
            git("--version");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
