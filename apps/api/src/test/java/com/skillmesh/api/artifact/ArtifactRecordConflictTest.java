package com.skillmesh.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import com.skillmesh.api.support.DatabaseCleaner;

/**
 * 同一份内容被并发提交时，制品记录只能有一条。
 *
 * 原来 record() 是「先查再插」：两台设备同时提交同一份内容，
 * 双双查到空，然后第二个插入撞 sha256 唯一约束——整个请求变成 500，
 * 明明内容一样、明明有唯一的一条记录该在。
 *
 * 现在插入交给数据库判（ON CONFLICT DO NOTHING），撞上了就当没插。
 */
@SpringBootTest
@ActiveProfiles("test")
class ArtifactRecordConflictTest {

    @Autowired private ArtifactService artifacts;
    @Autowired private ArtifactRepository repository;
    @Autowired private DatabaseCleaner cleaner;

    @BeforeEach
    void 清库() {
        cleaner.clean();
    }

    @Test
    void 同一份内容记两次只留一条() {
        byte[] zip = "zip".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = ArtifactService.sha256(zip);
        String key = artifacts.finalKey(sha);
        List<Map<String, Object>> manifest = List.of(
            Map.of("path", "SKILL.md", "sha256", sha, "size_bytes", 3L));

        var first = artifacts.record(sha, key, zip.length, 1, manifest);
        var second = artifacts.record(sha, key, zip.length, 1, manifest);

        // 两条记录是同一行：id 相同，不是新插了一条。
        assertThat(second.getId()).as("同一份内容只留一条记录").isEqualTo(first.getId());
        assertThat(repository.findAllStorageKeys()).as("库里只有一条引用").hasSize(1);
    }

    @Test
    void 清单原样落库() {
        byte[] zip = "zip".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = ArtifactService.sha256(zip);
        List<Map<String, Object>> manifest = List.of(
            Map.of("path", "SKILL.md", "sha256", sha, "size_bytes", 3L));

        var artifact = artifacts.record(sha, artifacts.finalKey(sha), zip.length, 1, manifest);

        // 走原生 SQL 插入的，清单得是能用的 jsonb，不是字符串。
        assertThat(artifact.getManifestJson()).as("清单原样落库")
            .hasSize(1)
            .first()
            .satisfies(item -> assertThat(item.get("path")).isEqualTo("SKILL.md"));
    }

    @Test
    void 不同的内容各记一条() {
        byte[] one = "one".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] two = "two".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String shaOne = ArtifactService.sha256(one);
        String shaTwo = ArtifactService.sha256(two);

        artifacts.record(shaOne, artifacts.finalKey(shaOne), one.length, 1, List.of());
        artifacts.record(shaTwo, artifacts.finalKey(shaTwo), two.length, 1, List.of());

        assertThat(repository.findAllStorageKeys()).as("不同内容各一条").hasSize(2);
    }
}
