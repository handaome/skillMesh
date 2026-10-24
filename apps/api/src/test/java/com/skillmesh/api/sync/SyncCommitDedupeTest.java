package com.skillmesh.api.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 落桶只有一个入口。
 *
 * 原来有两条写正式对象的路：整包上传按 `{skillId}/{version}/{摘要}.zip` 拼 key，
 * 同步提交按 `sync/{changesetId}/{摘要}.zip` 拼——两边都带版本或 changeset id，
 * 同一份内容会算出两个不同的 key，桶里各存一份，库里只有一条记录，
 * 第二个成了没人引用的孤儿。
 *
 * 现在正式对象按内容寻址，落桶统一走 {@code ArtifactService.store}。
 * 这条用例扫源码钉住「没有别处再自己拼正式对象的 key」——
 * 落桶的散点一旦回来，同一份内容就又会在桶里多一份。
 */
class SyncCommitDedupeTest {

    @Test
    void 正式对象的key只有store会生成() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            // 正式对象的 key 由 finalKey(sha256) 算，别的类都不该自己拼。
            if (text.contains("finalKey(") && !file.toString().contains("ArtifactService.java")) {
                offenders.add(file.getFileName().toString());
            }
        }
        assertThat(offenders)
            .as("正式对象的 key 只有 ArtifactService.finalKey 会生成").isEmpty();
    }

    @Test
    void 写桶的只有正式对象和临时对象两处() throws IOException {
        List<String> formal = new ArrayList<>();
        for (Path file : mainSources()) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (!text.contains("storage.put(")) {
                continue;
            }
            // 写临时对象是正当的：编辑时内容由服务端生成，没有客户端字节可传，
            // 得先落到临时位置再走 complete 转正。正式对象只该经 store()。
            if (!text.contains("storage.put(session.getTemporaryStorageKey()")
                && !file.toString().contains("ArtifactService.java")) {
                formal.add(file.getFileName().toString());
            }
        }
        assertThat(formal)
            .as("除了 ArtifactService.store，别处不该再直接写正式对象").isEmpty();
    }

    /** 主源码目录。 */
    private List<Path> mainSources() throws IOException {
        Path root = Path.of("src", "main", "java");
        try (var stream = Files.walk(root)) {
            return stream.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
