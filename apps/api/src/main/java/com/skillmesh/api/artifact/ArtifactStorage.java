package com.skillmesh.api.artifact;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** S3 对象存储访问。MinIO SDK 本身是通用 S3 客户端，不绑定 MinIO 服务端。 */
@Component
public class ArtifactStorage {

    private final MinioClient client;
    private final String bucket;

    public ArtifactStorage(MinioClient client,
                           @Value("${skillmesh.minio.bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
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
