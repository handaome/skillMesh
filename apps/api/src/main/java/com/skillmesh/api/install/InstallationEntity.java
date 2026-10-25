package com.skillmesh.api.install;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** installations 表：某个 Skill 装在哪台机器的哪个目录。 */
@Entity
@Table(name = "installations")
public class InstallationEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(name = "skill_version_id")
    private UUID skillVersionId;

    @Column(nullable = false)
    private String agent;

    @Column(name = "install_scope", nullable = false)
    private String installScope;

    @Column(name = "project_root")
    private String projectRoot;

    @Column(name = "target_path", nullable = false)
    private String targetPath;

    @Column(name = "installed_version")
    private String installedVersion;

    @Column(name = "installed_commit")
    private String installedCommit;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "installed_sha256", length = 64)
    private String installedSha256;

    @Column(name = "update_policy", nullable = false)
    private String updatePolicy;

    @Column(name = "local_modified", nullable = false)
    private boolean localModified;

    @Column(nullable = false)
    private String status;

    @Column(name = "installed_at", nullable = false)
    private OffsetDateTime installedAt;

    @Column(name = "last_checked_at")
    private OffsetDateTime lastCheckedAt;

    @Column(name = "last_updated_at")
    private OffsetDateTime lastUpdatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
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

    public UUID getSkillVersionId() {
        return skillVersionId;
    }

    public void setSkillVersionId(UUID skillVersionId) {
        this.skillVersionId = skillVersionId;
    }

    public String getAgent() {
        return agent;
    }

    public void setAgent(String agent) {
        this.agent = agent;
    }

    public String getInstallScope() {
        return installScope;
    }

    public void setInstallScope(String installScope) {
        this.installScope = installScope;
    }

    public String getProjectRoot() {
        return projectRoot;
    }

    public void setProjectRoot(String projectRoot) {
        this.projectRoot = projectRoot;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public void setTargetPath(String targetPath) {
        this.targetPath = targetPath;
    }

    public String getInstalledVersion() {
        return installedVersion;
    }

    public void setInstalledVersion(String installedVersion) {
        this.installedVersion = installedVersion;
    }

    public String getInstalledCommit() {
        return installedCommit;
    }

    public void setInstalledCommit(String installedCommit) {
        this.installedCommit = installedCommit;
    }

    public String getInstalledSha256() {
        return installedSha256;
    }

    public void setInstalledSha256(String installedSha256) {
        this.installedSha256 = installedSha256;
    }

    public String getUpdatePolicy() {
        return updatePolicy;
    }

    public void setUpdatePolicy(String updatePolicy) {
        this.updatePolicy = updatePolicy;
    }

    public boolean isLocalModified() {
        return localModified;
    }

    public void setLocalModified(boolean localModified) {
        this.localModified = localModified;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getInstalledAt() {
        return installedAt;
    }

    public void setInstalledAt(OffsetDateTime installedAt) {
        this.installedAt = installedAt;
    }

    public OffsetDateTime getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(OffsetDateTime lastCheckedAt) {
        this.lastCheckedAt = lastCheckedAt;
    }

    public OffsetDateTime getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(OffsetDateTime lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }
}
