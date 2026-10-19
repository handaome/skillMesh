package com.skillmesh.api.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** sync_conflicts 表：一次同步里云端发现的具体冲突点。 */
@Entity
@Table(name = "sync_conflicts")
public class SyncConflictEntity {

    @Id
    private UUID id;

    @Column(name = "changeset_id", nullable = false)
    private UUID changesetId;

    @Column(name = "relative_path", nullable = false)
    private String relativePath;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "base_sha256", length = 64)
    private String baseSha256;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "local_sha256", length = 64)
    private String localSha256;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "cloud_sha256", length = 64)
    private String cloudSha256;

    @Column(name = "conflict_type", nullable = false)
    private String conflictType;

    @Column(nullable = false)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resolution_json", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> resolutionJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    public static SyncConflictEntity of(UUID changesetId, String base, String local, String cloud) {
        SyncConflictEntity entity = new SyncConflictEntity();
        entity.setId(UUID.randomUUID());
        entity.setChangesetId(changesetId);
        // 变更集粒度是整个 Skill，冲突点就记在清单根上。
        entity.setRelativePath("SKILL.md");
        entity.setBaseSha256(blankToNull(base));
        entity.setLocalSha256(blankToNull(local));
        entity.setCloudSha256(blankToNull(cloud));
        entity.setConflictType("both_modified");
        entity.setStatus("open");
        entity.setResolutionJson(new LinkedHashMap<>());
        entity.setCreatedAt(OffsetDateTime.now());
        return entity;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public String getBaseSha256() {
        return baseSha256;
    }

    public void setBaseSha256(String baseSha256) {
        this.baseSha256 = baseSha256;
    }

    public String getLocalSha256() {
        return localSha256;
    }

    public void setLocalSha256(String localSha256) {
        this.localSha256 = localSha256;
    }

    public String getCloudSha256() {
        return cloudSha256;
    }

    public void setCloudSha256(String cloudSha256) {
        this.cloudSha256 = cloudSha256;
    }

    public String getConflictType() {
        return conflictType;
    }

    public void setConflictType(String conflictType) {
        this.conflictType = conflictType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, Object> getResolutionJson() {
        return resolutionJson;
    }

    public void setResolutionJson(Map<String, Object> resolutionJson) {
        this.resolutionJson = resolutionJson;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(OffsetDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}
