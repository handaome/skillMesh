package com.skillmesh.api.recycle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * skill_recycle_files 表：某个 Skill 某一版里被删掉的文件（设计文档 §6.4）。
 *
 * 只存元数据：文件内容还在旧版本的制品里（版本不可变，制品不会被删），
 * 恢复时从那里取字节重打一版。
 */
@Entity
@Table(name = "skill_recycle_files")
public class RecycleFileEntity {

    @Id
    private UUID id;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(nullable = false)
    private String path;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sha256", length = 64, nullable = false)
    private String sha256;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** 被删之前最后出现在哪一版：恢复时从它的制品里取内容。 */
    @Column(name = "source_version", nullable = false)
    private String sourceVersion;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** 回收期：过了这个点就真删，不再能恢复。 */
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSkillId() {
        return skillId;
    }

    public void setSkillId(UUID skillId) {
        this.skillId = skillId;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public void setSourceVersion(String sourceVersion) {
        this.sourceVersion = sourceVersion;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
