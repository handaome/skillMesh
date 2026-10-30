package com.skillmesh.api.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmesh.api.common.ContentDigest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 增量上传的合成：基线里没变的文件原样留着，变了的用新传的替换，
 * 本地删掉的从基线里去掉。
 *
 * 合错的后果是发布一份缺文件或多了旧文件的版本——用户装下来才发现内容不对。
 */
class SyncUploadServiceMergeTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha(String text) {
        return ContentDigest.sha256(bytes(text));
    }

    @Test
    void 没变的沿用基线变的用新传的删掉的去掉() {
        Map<String, byte[]> baseline = new LinkedHashMap<>();
        baseline.put("SKILL.md", bytes("old-skill"));
        baseline.put("guide.md", bytes("same"));
        baseline.put("gone.md", bytes("bye"));
        List<SyncUploadService.UploadedFile> uploaded =
            List.of(new SyncUploadService.UploadedFile("SKILL.md", bytes("new-skill")));
        Map<String, String> manifest = new LinkedHashMap<>();
        manifest.put("SKILL.md", sha("new-skill"));
        manifest.put("guide.md", sha("same"));

        List<SyncUploadService.UploadedFile> merged =
            SyncUploadService.mergeFiles(baseline, uploaded, manifest);

        assertThat(merged).hasSize(2);
        Map<String, String> contents = new LinkedHashMap<>();
        for (SyncUploadService.UploadedFile file : merged) {
            contents.put(file.path(), new String(file.data(), StandardCharsets.UTF_8));
        }
        assertThat(contents).containsEntry("SKILL.md", "new-skill");
        assertThat(contents).containsEntry("guide.md", "same");
        assertThat(contents).doesNotContainKey("gone.md");
    }

    @Test
    void 全量上传时新传的盖掉基线里的同名文件() {
        Map<String, byte[]> baseline = new LinkedHashMap<>();
        baseline.put("SKILL.md", bytes("old-skill"));
        List<SyncUploadService.UploadedFile> uploaded =
            List.of(new SyncUploadService.UploadedFile("SKILL.md", bytes("new-skill")));
        Map<String, String> manifest = Map.of("SKILL.md", sha("new-skill"));

        List<SyncUploadService.UploadedFile> merged =
            SyncUploadService.mergeFiles(baseline, uploaded, manifest);

        assertThat(merged).hasSize(1);
        assertThat(new String(merged.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("new-skill");
    }

    @Test
    void 没有基线时传上来的就是全部() {
        List<SyncUploadService.UploadedFile> uploaded =
            List.of(new SyncUploadService.UploadedFile("SKILL.md", bytes("only")));
        Map<String, String> manifest = Map.of("SKILL.md", sha("only"));

        List<SyncUploadService.UploadedFile> merged =
            SyncUploadService.mergeFiles(Map.of(), uploaded, manifest);

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).path()).isEqualTo("SKILL.md");
    }
}
