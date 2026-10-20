package com.skillmesh.api.diff;

import com.skillmesh.api.artifact.ArtifactEntity;
import com.skillmesh.api.artifact.ArtifactRepository;
import com.skillmesh.api.artifact.ArtifactStorage;
import com.skillmesh.api.version.SkillVersionEntity;
import com.skillmesh.api.version.SkillVersionRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 版本之间的文件级差异（设计文档 §3.3）。
 *
 * 文件级比 manifest 就行，不解包；只有「被改动的文件」才从两个 zip 里
 * 各取一个 entry 算行数。口径与 CLI 的 update --dry-run 对齐。
 */
@Service
public class ArtifactDiffService {

    /** 单个 entry 超过这个大小就不做行统计。 */
    private static final int MAX_ENTRY_BYTES = 2 << 20;

    private final ArtifactRepository artifacts;
    private final ArtifactStorage storage;
    private final SkillVersionRepository versions;

    public ArtifactDiffService(ArtifactRepository artifacts, ArtifactStorage storage,
                               SkillVersionRepository versions) {
        this.artifacts = artifacts;
        this.storage = storage;
        this.versions = versions;
    }

    /**
     * 待审版本 vs 该 Skill 最近的已发布版本。
     *
     * {@code NOT_SUPPORTED}：算差异要下载两个 zip，事务期间占着数据库连接
     * 会把连接池拖垮。仓库查询各自是独立短事务，下载时线程上没有事务。
     *
     * 事务外只碰基本字段（{@code manifestJson} 是 jsonb 基本类型、
     * {@code storageKey} 是普通列），没有懒加载，不会有
     * {@code LazyInitializationException}。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ArtifactDiffResult diffAgainstLatestPublished(UUID skillId, SkillVersionEntity next) {
        Optional<SkillVersionEntity> base = loadBase(skillId, next);
        String nextKey = storageKeyOf(next.getArtifactId());
        if (nextKey == null) {
            return ArtifactDiffResult.empty();
        }
        if (base.isEmpty()) {
            List<FileChange> files = ManifestDiff.compare(List.of(), next.getManifestJson());
            return new ArtifactDiffResult("", false, files);
        }
        SkillVersionEntity baseVersion = base.get();
        String baseKey = storageKeyOf(baseVersion.getArtifactId());
        if (baseKey == null) {
            return new ArtifactDiffResult(baseVersion.getVersion(), false, List.of());
        }
        List<FileChange> files = withLineCounts(
            ManifestDiff.compare(baseVersion.getManifestJson(), next.getManifestJson()),
            baseKey, nextKey);
        return new ArtifactDiffResult(baseVersion.getVersion(), truncated(files), files);
    }

    /**
     * 读制品里的 SKILL.md 正文——审核者要看的第一眼就是它。
     *
     * 同样 {@code NOT_SUPPORTED}：下载 zip 不该占着数据库连接。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String readSkillMarkdown(ArtifactEntity artifact) {
        if (artifact == null) {
            return "";
        }
        byte[] data = readEntry(artifact.getStorageKey(), "SKILL.md");
        return data == null ? "" : new String(data, StandardCharsets.UTF_8);
    }

    /**
     * 挑基线：该 Skill 最近的已发布版本（不含待审的那个自己）。
     *
     * 清单为空的已发布版本要跳过——拿它当基线，整份 diff 会退化成「全部 added」，
     * 调用方分不清「没有基线」和「基线清单是空的」。跟 SyncUploadService.cloudRevision 同一个口径。
     */
    private Optional<SkillVersionEntity> loadBase(UUID skillId, SkillVersionEntity next) {
        return versions.findBySkillIdOrderByCreatedAtDesc(skillId).stream()
            .filter(version -> "published".equals(version.getStatus()))
            .filter(version -> !version.getId().equals(next.getId()))
            .filter(version -> version.getManifestJson() != null
                && !version.getManifestJson().isEmpty())
            .findFirst();
    }

    /** 给被改动的文件补上行数。 */
    private List<FileChange> withLineCounts(List<FileChange> files, String baseKey,
                                            String nextKey) {
        List<FileChange> result = new ArrayList<>(files.size());
        for (FileChange change : files) {
            if (!"modified".equals(change.kind())) {
                result.add(change);
                continue;
            }
            byte[] oldData = readEntry(baseKey, change.path());
            byte[] newData = readEntry(nextKey, change.path());
            if (oldData == null || newData == null
                || LineDiff.isBinary(oldData) || LineDiff.isBinary(newData)) {
                result.add(FileChange.modified(change.path(), 0, 0, true));
                continue;
            }
            // 按字节比：ISO-8859-1 是字节到字符的一一映射，行比较与 CLI 的
            // bytes.Equal 完全一致；用 UTF-8 解码会把非法字节都折成 U+FFFD，
            // 两份不同的文件可能算出「没有差异」。
            int[] counts = LineDiff.count(LineDiff.decode(oldData), LineDiff.decode(newData));
            if (counts == null) {
                // LCS 表太大算不动——只报文件级，跟 CLI 一样。
                result.add(FileChange.modified(change.path(), 0, 0, true));
                continue;
            }
            boolean truncated = counts[0] + counts[1] > LineDiff.MAX_LINES;
            result.add(FileChange.modified(change.path(), counts[0], counts[1], truncated));
        }
        return result;
    }

    /**
     * 从 zip 里取一个 entry 的字节。取不到返回 null。
     *
     * 制品读不出来不是错误——只报文件级就行，别把整个审核页拖垮。
     * ponytail: 每个改动文件各读一次 zip，改动多了就是 N 次对象存储读取；
     * 一次读整个 zip 换成 map 更省，但内存峰值按改动文件总长涨。
     */
    private byte[] readEntry(String storageKey, String path) {
        try (InputStream stream = storage.get(storageKey);
             ZipInputStream zip = new ZipInputStream(stream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !path.equals(entry.getName())) {
                    continue;
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = zip.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                    if (buffer.size() > MAX_ENTRY_BYTES) {
                        return null;
                    }
                }
                return buffer.toByteArray();
            }
        } catch (IOException | IllegalStateException e) {
            // IOException：zip 坏了。IllegalStateException：对象存储读不到。
            return null;
        }
        return null;
    }

    /** 有一个文件只报文件级，整个 diff 就算不完整。 */
    private boolean truncated(List<FileChange> files) {
        return files.stream().anyMatch(FileChange::truncated);
    }

    /** 制品的存储键。制品不存在返回 null。 */
    private String storageKeyOf(UUID artifactId) {
        if (artifactId == null) {
            return null;
        }
        return artifacts.findById(artifactId).map(ArtifactEntity::getStorageKey).orElse(null);
    }
}
