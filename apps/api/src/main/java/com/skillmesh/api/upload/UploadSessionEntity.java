package com.skillmesh.api.upload;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 上传会话。服务端记住临时对象 key 与期望摘要，客户端 complete 时不必回传。 */
@Entity
@Table(name = "upload_sessions")
public class UploadSessionEntity {

    @Id
    private UUID id;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(nullable = false)
    private String purpose;

    @Column(name = "skill_version_id")
    private UUID skillVersionId;

    @Column(name = "changeset_id")
    private UUID changesetId;

    @Column(name = "relative_path")
    private String relativePath;

    // DDL 里是 char(64)，显式指定 CHAR 才过得了 schema 校验。
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "expected_sha256", length = 64)
    private String expectedSha256;

    @Column(name = "expected_size_bytes", nullable = false)
    private long expectedSizeBytes;

    @Column(name = "temporary_storage_key", nullable = false, unique = true)
    private String temporaryStorageKey;

    @Column(nullable = false)
    private String status;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "uploaded_at")
    private OffsetDateTime uploadedAt;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(UUID actorUserId) {
        this.actorUserId = actorUserId;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public UUID getSkillVersionId() {
        return skillVersionId;
    }

    public void setSkillVersionId(UUID skillVersionId) {
        this.skillVersionId = skillVersionId;
    }

    public UUID getChangesetId() {
        return changesetId;
    }

    public void setChangesetId(UUID changesetId) {
        this.changesetId = changesetId;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public String getExpectedSha256() {
        return expectedSha256;
    }

    public void setExpectedSha256(String expectedSha256) {
        this.expectedSha256 = expectedSha256;
    }

    public long getExpectedSizeBytes() {
        return expectedSizeBytes;
    }

    public void setExpectedSizeBytes(long expectedSizeBytes) {
        this.expectedSizeBytes = expectedSizeBytes;
    }

    public String getTemporaryStorageKey() {
        return temporaryStorageKey;
    }

    public void setTemporaryStorageKey(String temporaryStorageKey) {
        this.temporaryStorageKey = temporaryStorageKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public OffsetDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(OffsetDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public OffsetDateTime getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(OffsetDateTime verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
