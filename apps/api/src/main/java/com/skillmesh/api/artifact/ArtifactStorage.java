package com.skillmesh.api.artifact;

import io.minio.BucketExistsArgs;
import io.minio.GetBucketLifecycleArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketLifecycleArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.Expiration;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.LifecycleRule;
import io.minio.messages.ResponseDate;
import io.minio.messages.RuleFilter;
import io.minio.messages.Status;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** S3 对象存储访问。MinIO SDK 本身是通用 S3 客户端，不绑定 MinIO 服务端。 */
@Component
public class ArtifactStorage {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStorage.class);

    private final MinioClient client;
    private final String bucket;
    private final int tempExpireDays;

    public ArtifactStorage(MinioClient client,
                           @Value("${skillmesh.minio.bucket}") String bucket,
                           @Value("${skillmesh.minio.temp-expire-days:1}") int tempExpireDays) {
        this.client = client;
        this.bucket = bucket;
        this.tempExpireDays = tempExpireDays;
    }

    public String bucket() {
        return bucket;
    }

    public void ensureBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("对象存储 bucket 不可用: " + bucket, e);
        }
        ensureTempLifecycle();
    }

    /**
     * 给 bucket 挂一条生命周期规则：{@code tmp/} 下的对象几天后自动清掉。
     *
     * 上传会话 30 分钟就过期，但**对象不会自己消失**——没人来收的话，
     * 对象存储里的 tmp/ 一直涨。
     *
     * bucket 上已经有生命周期规则就不动它：部署方自己配的规则优先，
     * 别用我们的覆盖人家的。
     */
    private void ensureTempLifecycle() {
        try {
            LifecycleConfiguration current = client.getBucketLifecycle(
                GetBucketLifecycleArgs.builder().bucket(bucket).build());
            if (current != null && !current.rules().isEmpty()) {
                return;
            }
        } catch (Exception e) {
            // bucket 上还没有生命周期配置——那正是要挂规则的情况，接着往下走。
            if (!isNoLifecycle(e)) {
                log.warn("读 bucket 生命周期规则失败: {}", e.getMessage());
                return;
            }
        }
        try {
            client.setBucketLifecycle(SetBucketLifecycleArgs.builder()
                .bucket(bucket)
                .config(new LifecycleConfiguration(List.of(new LifecycleRule(
                    Status.ENABLED,
                    null,
                    new Expiration((ResponseDate) null, tempExpireDays, null),
                    new RuleFilter("tmp/"),
                    "expire-temp-objects",
                    null, null, null))))
                .build());
        } catch (Exception e) {
            // 挂不上不影响主流程：只是临时对象清得没那么及时。
            log.warn("给 bucket 挂临时对象生命周期规则失败: {}", e.getMessage());
        }
    }

    /** bucket 上还没有生命周期配置时，对象存储是这么说的。 */
    private boolean isNoLifecycle(Exception e) {
        if (e instanceof ErrorResponseException response) {
            return "NoSuchLifecycleConfiguration".equals(response.errorResponse().code());
        }
        return false;
    }

    public String presignedPut(String key, Duration ttl) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.PUT).bucket(bucket).object(key)
                .expiry((int) ttl.toSeconds()).build());
        } catch (Exception e) {
            throw new IllegalStateException("生成上传地址失败: " + key, e);
        }
    }

    public String presignedGet(String key, Duration ttl) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET).bucket(bucket).object(key)
                .expiry((int) ttl.toSeconds()).build());
        } catch (Exception e) {
            throw new IllegalStateException("生成下载地址失败: " + key, e);
        }
    }

    public void put(String key, byte[] data, String contentType) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            client.putObject(PutObjectArgs.builder()
                .bucket(bucket).object(key)
                .stream(in, data.length, -1)
                .contentType(contentType)
                .build());
        } catch (Exception e) {
            throw new IllegalStateException("写入对象失败: " + key, e);
        }
    }

    public InputStream get(String key) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new IllegalStateException("读取对象失败: " + key, e);
        }
    }

    public boolean exists(String key) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 对象大小（字节）。对象不存在时返回 -1。
     *
     * 用来在把内容读进堆之前先问一句「它多大」——
     * 预签名地址在有效期内还能继续 PUT，对象可能比声明的大得多。
     */
    public long size(String key) {
        try {
            return client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()).size();
        } catch (Exception e) {
            return -1L;
        }
    }

    public void delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception ignored) {
            // 临时对象清理失败不阻断主流程
        }
    }
}
