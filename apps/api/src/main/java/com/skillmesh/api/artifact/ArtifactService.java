package com.skillmesh.api.artifact;

import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArtifactService {

    public static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);
    public static final Duration UPLOAD_TTL = Duration.ofMinutes(30);
    public static final long MAX_ARTIFACT_BYTES = 52_428_800L;

    private final ArtifactStorage storage;
    private final ArtifactRepository artifacts;

    public ArtifactService(ArtifactStorage storage, ArtifactRepository artifacts) {
        this.storage = storage;
        this.artifacts = artifacts;
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

    public String finalKey(UUID skillId, String version, String sha256) {
        return "%s/%s/%s.zip".formatted(skillId, version, sha256);
    }

    /**
     * 把临时对象转正：重算摘要、比对声明值、写入正式地址、删除临时对象。
     * 摘要不匹配就删临时对象并报错——不留下未验证的残留（详细设计 §13）。
     */
    @Transactional
    public Promoted promote(String tempKey, String finalKey, String expectedSha256,
                            long expectedSizeBytes) {
        if (!storage.exists(tempKey)) {
            throw new ApiException(422, ErrorCodes.ARTIFACT_MISSING,
                "上传对象不存在或已过期，请重新上传");
        }
        byte[] bytes = readAll(storage.get(tempKey));
        if (bytes.length > MAX_ARTIFACT_BYTES) {
            storage.delete(tempKey);
            throw new ApiException(422, ErrorCodes.VALIDATION_FAILED, "制品超过大小上限");
        }
        if (expectedSizeBytes > 0 && bytes.length != expectedSizeBytes) {
            storage.delete(tempKey);
            throw new ApiException(422, ErrorCodes.DIGEST_MISMATCH,
                "制品大小与声明不一致：声明 " + expectedSizeBytes + "，实际 " + bytes.length);
        }
        String actual = sha256(bytes);
        if (!actual.equals(expectedSha256)) {
            storage.delete(tempKey);
            throw new ApiException(422, ErrorCodes.DIGEST_MISMATCH,
                "制品摘要不匹配：声明 " + expectedSha256 + "，实际 " + actual);
        }
        storage.put(finalKey, bytes, "application/zip");
        storage.delete(tempKey);
        return new Promoted(actual, bytes.length);
    }

    /** 内容寻址：同一份内容只留一条 artifact 记录。 */
    @Transactional
    public ArtifactEntity record(String sha256, String storageKey, long sizeBytes,
                                 int fileCount, List<java.util.Map<String, Object>> manifest) {
        return artifacts.findBySha256(sha256).orElseGet(() -> {
            ArtifactEntity artifact = new ArtifactEntity();
            artifact.setId(UUID.randomUUID());
            artifact.setSha256(sha256);
            artifact.setStorageKey(storageKey);
            artifact.setContentType("application/zip");
            artifact.setSizeBytes(sizeBytes);
            artifact.setFileCount(fileCount);
            artifact.setManifestJson(manifest);
            artifact.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            return artifacts.save(artifact);
        });
    }

    @Transactional
    public ArtifactEntity saveArtifact(ArtifactEntity artifact) {
        return artifacts.save(artifact);
    }

    @Transactional(readOnly = true)
    public ArtifactEntity require(UUID artifactId) {
        return artifacts.findById(artifactId).orElseThrow(() -> ApiException.notFound("Artifact"));
    }

    public String presignedDownload(ArtifactEntity artifact) {
        return storage.presignedGet(artifact.getStorageKey(), DOWNLOAD_TTL);
    }

    public InputStream open(ArtifactEntity artifact) {
        return storage.get(artifact.getStorageKey());
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取上传对象失败", e);
        }
    }

    /** 校验通过后的制品信息。 */
    public record Promoted(String sha256, long sizeBytes) {}
}
