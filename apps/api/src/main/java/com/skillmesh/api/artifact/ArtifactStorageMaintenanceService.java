package com.skillmesh.api.artifact;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 桶里的孤儿对象：桶里有、库里没人引用的对象。
 *
 * 内容寻址之后不会再产生新的孤儿（同一份内容只写一次），
 * 但历史遗留的那批还在桶里占地方——它们既没人引用，也没人删。
 *
 * 判定：列出桶里所有对象，减去 artifacts 表里引用的 storage_key。
 * 剩下的就是孤儿。
 *
 * 只清「正式对象」区（{@code sha256/} 开头）：临时对象由生命周期规则管，
 * 删它们会打断正在传的上传。
 */
@Service
public class ArtifactStorageMaintenanceService {

    private static final Logger log =
        LoggerFactory.getLogger(ArtifactStorageMaintenanceService.class);

    /**
     * 宽限期：比这还新的对象一律不动。
     *
     * 一天足够长——发布流程里「对象写进桶」到「artifacts 行提交」之间最多几秒。
     */
    public static final Duration GRACE_PERIOD = Duration.ofDays(1);

    private final MinioClient minio;
    private final ArtifactRepository artifacts;
    private final ArtifactStorage storage;
    private final String bucket;

    public ArtifactStorageMaintenanceService(MinioClient minio, ArtifactRepository artifacts,
                                             ArtifactStorage storage,
                                             @Value("${skillmesh.minio.bucket}") String bucket) {
        this.minio = minio;
        this.artifacts = artifacts;
        this.storage = storage;
        this.bucket = bucket;
    }

    /**
     * 找出桶里的孤儿对象。
     *
     * 只看正式对象区：临时对象（tmp/、sync-staging/）由生命周期规则清，
     * 这里动它们会打断正在传的上传。
     *
     * **有宽限期**：刚 promote 上去、还没提交 artifacts 行的对象也是「没人引用」的——
     * 那一瞬间它确实还没被引用，但下一次请求就会引用它。
     * 只删比宽限期还老的对象，才不会把正在发布的内容删掉。
     */
    public List<String> findOrphans() {
        Set<String> referenced = new java.util.HashSet<>(artifacts.findAllStorageKeys());
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(GRACE_PERIOD);
        List<String> orphans = new ArrayList<>();
        try {
            Iterable<Result<Item>> listed = minio.listObjects(ListObjectsArgs.builder()
                .bucket(bucket).recursive(true).build());
            for (Result<Item> result : listed) {
                Item item = result.get();
                String key = item.objectName();
                if (key.startsWith("tmp/") || key.startsWith("sync-staging/")) {
                    continue;
                }
                if (referenced.contains(key)) {
                    continue;
                }
                // 刚写的对象先放过：它可能正在被发布流程引用。
                if (item.lastModified() == null
                    || item.lastModified().toInstant().isAfter(cutoff.toInstant())) {
                    continue;
                }
                orphans.add(key);
            }
        } catch (Exception e) {
            throw new IllegalStateException("列桶里的对象失败: " + bucket, e);
        }
        return orphans;
    }

    /**
     * 删掉孤儿对象，返回删了几个。
     *
     * 默认只报不删——删桶里的东西是不可逆的运维动作，
     * 得让人明确说了才做。
     */
    public int purgeOrphans(boolean dryRun) {
        List<String> orphans = findOrphans();
        if (orphans.isEmpty()) {
            log.info("桶里没有孤儿对象");
            return 0;
        }
        if (dryRun) {
            log.info("桶里有 {} 个孤儿对象（演练，未删除）：{}", orphans.size(), orphans);
            return 0;
        }
        for (String key : orphans) {
            storage.delete(key);
        }
        log.info("清掉 {} 个孤儿对象", orphans.size());
        return orphans.size();
    }
}
