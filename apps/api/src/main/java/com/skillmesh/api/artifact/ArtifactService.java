package com.skillmesh.api.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ContentDigest;
import com.skillmesh.api.common.ErrorCodes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArtifactService {

    public static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);
    public static final Duration UPLOAD_TTL = Duration.ofMinutes(30);

    /** 制品大小上限的默认值（50 MiB）。运维可按环境调（设计文档 §14）。 */
    public static final long DEFAULT_MAX_ARTIFACT_BYTES = 52_428_800L;

    private final ArtifactStorage storage;
    private final ArtifactRepository artifacts;
    private final ObjectMapper json;
    /** 单个制品的大小上限。 */
    private final long maxArtifactBytes;

    public ArtifactService(ArtifactStorage storage, ArtifactRepository artifacts,
                           ObjectMapper json,
                           @Value("${skillmesh.artifact.max-bytes:52428800}") long maxArtifactBytes) {
        this.storage = storage;
        this.artifacts = artifacts;
        this.json = json;
        this.maxArtifactBytes = maxArtifactBytes <= 0
            ? DEFAULT_MAX_ARTIFACT_BYTES : maxArtifactBytes;
        storage.ensureBucket();
    }


    /**
     * 临时对象与正式对象分开：临时对象设过期，正式对象按摘要寻址且不可覆盖（附录 A.2）。
     *
     * 后缀是 .zip：制品本来就是 zip——CLI 用 archive/zip 打包，
     * content_type 是 application/zip，服务端用 ZipInputStream 扫描。
     * （这里曾误写成 .tar.gz，存量制品的 key 记在 artifacts.storage_key，
     *   下载走库里的 key，不受影响。）
     */
    public String tempKey(UUID skillId, UUID versionId) {
        return "tmp/%s/%s/%s.zip".formatted(skillId, versionId, UUID.randomUUID());
    }

    /**
     * 正式对象按摘要寻址：同一份内容永远只有一个地址（附录 A.2）。
     *
     * 早先的地址带 Skill 与版本（{@code %s/%s/%s.zip}），同一份内容发到两个版本
     * 会算出两个 key，桶里留下两份、库里只有一条记录，第二份成了没人清理的孤儿
     * （bucket 生命周期只清 tmp/ 前缀）。
     *
     * 存量制品的 key 记在 artifacts.storage_key 里，下载走库里的 key，不受影响。
     */
    public String finalKey(String sha256) {
        return "sha256/%s.zip".formatted(sha256);
    }

    /**
     * 把临时对象转正：重算摘要、比对声明值、写入正式地址、删除临时对象。
     * 摘要不匹配就删临时对象并报错——不留下未验证的残留（详细设计 §13）。
     * 同一份内容已经有正式对象时复用它，不再写第二份（见 store）。
     */
    @Transactional
    public Promoted promote(String tempKey, String expectedSha256, long expectedSizeBytes) {
        if (!storage.exists(tempKey)) {
            throw new ApiException(422, ErrorCodes.ARTIFACT_MISSING,
                "上传对象不存在或已过期，请重新上传");
        }
        // 先问对象多大：预签名地址在有效期内还能继续 PUT，对象可能比声明的大得多。
        // 不先看一眼就整包读进堆，一个大对象就能把堆撑爆。
        long storedSize = storage.size(tempKey);
        if (storedSize > maxArtifactBytes) {
            storage.delete(tempKey);
            throw new ApiException(422, ErrorCodes.VALIDATION_FAILED, "制品超过大小上限");
        }
        byte[] bytes;
        try {
            bytes = readAll(storage.get(tempKey));
        } catch (ApiException e) {
            storage.delete(tempKey);
            throw e;
        } catch (IOException e) {
            storage.delete(tempKey);
            throw new ApiException(502, ErrorCodes.ARTIFACT_MISSING, "读取上传对象失败");
        }
        if (expectedSizeBytes > 0 && bytes.length != expectedSizeBytes) {
            storage.delete(tempKey);
            throw new ApiException(422, ErrorCodes.DIGEST_MISMATCH,
                "制品大小与声明不一致：声明 " + expectedSizeBytes + "，实际 " + bytes.length);
        }
        String actual = ContentDigest.sha256(bytes);
        if (!actual.equals(expectedSha256)) {
            storage.delete(tempKey);
            throw new ApiException(422, ErrorCodes.DIGEST_MISMATCH,
                "制品摘要不匹配：声明 " + expectedSha256 + "，实际 " + actual);
        }
        // 同一份内容已经有正式对象就复用它，不往桶里再写一份。
        String key = store(actual, bytes);
        storage.delete(tempKey);
        return new Promoted(actual, bytes.length, key);
    }

    /**
     * 内容寻址落桶：同一份内容在桶里只留一份。
     *
     * 已经有同摘要的制品就复用它的 storage_key，不再写对象——
     * 否则同一份内容发到两个版本（或两台设备各提交一次同步），
     * 桶里会多出一份没人删的孤儿对象，而库里只有一条记录指向第一个。
     *
     * @return 正式对象的 key：复用时是已有记录的 key
     */
    @Transactional
    public String store(String sha256, byte[] bytes) {
        ArtifactEntity existing = artifacts.findBySha256(sha256).orElse(null);
        String key = existing != null ? existing.getStorageKey() : finalKey(sha256);
        // 库里有记录但对象不在（对象被清掉了）：按原 key 补写一次，key 不变。
        if (existing == null || !storage.exists(key)) {
            storage.put(key, bytes, "application/zip");
        }
        return key;
    }

    /**
     * 内容寻址：同一份内容只留一条 artifact 记录。
     *
     * 插入由数据库判重复（ON CONFLICT DO NOTHING）：两台设备同时提交同一份内容时，
     * 「先查再插」会双双查到空，然后第二个插入撞 sha256 唯一约束变成 500。
     * 让约束来判，撞上了就当没插，再读一次读到的就是先到那条。
     */
    @Transactional
    public ArtifactEntity record(String sha256, String storageKey, long sizeBytes,
                                 int fileCount, List<java.util.Map<String, Object>> manifest) {
        ArtifactEntity existing = artifacts.findBySha256(sha256).orElse(null);
        if (existing != null) {
            return existing;
        }
        artifacts.insertIfAbsent(UUID.randomUUID(), sha256, storageKey, sizeBytes,
            fileCount, toJson(manifest));
        return artifacts.findBySha256(sha256)
            .orElseThrow(() -> new ApiException(500, ErrorCodes.INTERNAL,
                "制品记录写入后读不到"));
    }

    /** 清单转成 jsonb 文本：原生 SQL 插不进 List，得自己序列。 */
    private String toJson(List<java.util.Map<String, Object>> manifest) {
        try {
            return json.writeValueAsString(manifest);
        } catch (Exception e) {
            throw new ApiException(500, ErrorCodes.INTERNAL, "清单序列化失败");
        }
    }

    @Transactional
    public ArtifactEntity saveArtifact(ArtifactEntity artifact) {
        return artifacts.save(artifact);
    }

    @Transactional(readOnly = true)
    public ArtifactEntity require(UUID artifactId) {
        return artifacts.findById(artifactId).orElseThrow(() -> ApiException.notFound("Artifact"));
    }

    /**
     * 批量取制品，按 id 归拢。
     *
     * 列表页一次拿齐：逐个版本查一次，在远程库上就是 N 次往返
     * （本机到库的往返几十毫秒，版本一多页面就卡住）。
     * 查不到的 id 不在这里报错——调用方按自己的口径处理。
     */
    @Transactional(readOnly = true)
    public Map<UUID, ArtifactEntity> byIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ArtifactEntity> out = new LinkedHashMap<>();
        for (ArtifactEntity artifact : artifacts.findAllById(ids)) {
            out.put(artifact.getId(), artifact);
        }
        return out;
    }

    public String presignedDownload(ArtifactEntity artifact) {
        return storage.presignedGet(artifact.getStorageKey(), DOWNLOAD_TTL);
    }

    public InputStream open(ArtifactEntity artifact) {
        return storage.get(artifact.getStorageKey());
    }

    /**
     * 有上限地读一个对象。
     *
     * 不用 readAllBytes：对象大小不由我们决定（预签名地址还能继续 PUT），
     * 读进堆的字节必须有上限——不然一个大对象就够把服务打死。
     */
    private byte[] readAll(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                if (out.size() + (long) read > maxArtifactBytes) {
                    throw new ApiException(422, ErrorCodes.VALIDATION_FAILED, "制品超过大小上限");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    /** 校验通过后的制品信息。 */
    public record Promoted(String sha256, long sizeBytes, String storageKey) {}
}
