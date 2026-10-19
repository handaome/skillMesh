package com.skillmesh.api.artifact;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "artifacts")
public class ArtifactEntity {

    @Id
    private UUID id;

    // DDL 里是 char(64)（bpchar），Hibernate 按 Java 类型会期望 varchar，
    // 显式指定 CHAR 才过得了 schema 校验。
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 64, nullable = false, unique = true)
    private String sha256;

    @Column(name = "storage_key", nullable = false, unique = true)
    private String storageKey;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "file_count", nullable = false)
    private int fileCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manifest_json", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> manifestJson;

    private String signature;

    @Column(name = "signature_key_id")
    private String signatureKeyId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public int getFileCount() {
        return fileCount;
    }

    public void setFileCount(int fileCount) {
        this.fileCount = fileCount;
    }

    public List<Map<String, Object>> getManifestJson() {
        return manifestJson;
    }

    public void setManifestJson(List<Map<String, Object>> manifestJson) {
        this.manifestJson = manifestJson;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getSignatureKeyId() {
        return signatureKeyId;
    }

    public void setSignatureKeyId(String signatureKeyId) {
        this.signatureKeyId = signatureKeyId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
