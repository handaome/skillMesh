package com.skillmesh.api.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManifestDiffTest {

    @Test
    void 认出新增修改删除() {
        List<Map<String, Object>> base = List.of(
            entry("SKILL.md", "aaa"),
            entry("old.sh", "bbb"));
        List<Map<String, Object>> next = List.of(
            entry("SKILL.md", "ccc"),
            entry("new.md", "ddd"));

        List<FileChange> changes = ManifestDiff.compare(base, next);

        assertThat(changes).hasSize(3);
        assertThat(changes).extracting(FileChange::path)
            .containsExactly("SKILL.md", "new.md", "old.sh");
        assertThat(changes).filteredOn(c -> c.path().equals("SKILL.md"))
            .singleElement().extracting(FileChange::kind).isEqualTo("modified");
        assertThat(changes).filteredOn(c -> c.path().equals("new.md"))
            .singleElement().extracting(FileChange::kind).isEqualTo("added");
        assertThat(changes).filteredOn(c -> c.path().equals("old.sh"))
            .singleElement().extracting(FileChange::kind).isEqualTo("removed");
    }

    @Test
    void 摘要相同就是没改() {
        List<Map<String, Object>> base = List.of(entry("SKILL.md", "aaa"));
        assertThat(ManifestDiff.compare(base, base)).isEmpty();
    }

    @Test
    void 空基线时全部算新增() {
        List<Map<String, Object>> next = List.of(entry("SKILL.md", "aaa"));
        assertThat(ManifestDiff.compare(List.of(), next))
            .singleElement().extracting(FileChange::kind).isEqualTo("added");
    }

    private static Map<String, Object> entry(String path, String sha) {
        return Map.of("path", path, "sha256", sha, "size_bytes", 10, "executable", false);
    }
}
