package com.skillmesh.api.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** sync_states 表：某个设备上某个 Skill 的同步基线（设计文档 §2 的「同步基线」）。 */
@Entity
@Table(name = "sync_states")
public class SyncStateEntity {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(name = "local_path", nullable = false)
    private String localPath;

    @Column(name = "local_revision")
    private String localRevision;

    @Column(name = "cloud_revision")
    private String cloudRevision;

    @Column(name = "base_revision")
    private String baseRevision;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "local_manifest_hash", length = 64)
    private String localManifestHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cloud_manifest_hash", length = 64)
    private String cloudManifestHash;

    @Column(nullable = false)
    private String status;

    @Column(name = "last_synced_at")
    private OffsetDateTime lastSyncedAt;

    @Column(name = "last_error_code")
    private String lastErrorCode;

    @Column(name = "last_error_message")
    private String lastErrorMessage;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public UUID getSkillId() {
        return skillId;
    }

    public void setSkillId(UUID skillId) {
        this.skillId = skillId;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public String getLocalRevision() {
        return localRevision;
    }

    public void setLocalRevision(String localRevision) {
        this.localRevision = localRevision;
    }

    public String getCloudRevision() {
        return cloudRevision;
    }

    public void setCloudRevision(String cloudRevision) {
        this.cloudRevision = cloudRevision;
    }

    public String getBaseRevision() {
        return baseRevision;
    }

    public void setBaseRevision(String baseRevision) {
        this.baseRevision = baseRevision;
    }

    public String getLocalManifestHash() {
        return localManifestHash;
    }

    public void setLocalManifestHash(String localManifestHash) {
        this.localManifestHash = localManifestHash;
    }

    public String getCloudManifestHash() {
        return cloudManifestHash;
    }

    public void setCloudManifestHash(String cloudManifestHash) {
        this.cloudManifestHash = cloudManifestHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(OffsetDateTime lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public void setLastErrorCode(String lastErrorCode) {
        this.lastErrorCode = lastErrorCode;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public void setLastErrorMessage(String lastErrorMessage) {
        this.lastErrorMessage = lastErrorMessage;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
