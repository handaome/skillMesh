package com.skillmesh.api.scan;

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

/** skill_scans 表：一个版本的每一次扫描结论。 */
@Entity
@Table(name = "skill_scans")
public class ScanEntity {

    @Id
    private UUID id;

    @Column(name = "skill_version_id", nullable = false)
    private UUID skillVersionId;

    @Column(name = "scanner_name", nullable = false)
    private String scannerName;

    @Column(name = "scanner_version", nullable = false)
    private String scannerVersion;

    @Column(nullable = false)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "findings_json", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> findingsJson;

    @Column(nullable = false)
    private String summary;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSkillVersionId() {
        return skillVersionId;
    }

    public void setSkillVersionId(UUID skillVersionId) {
        this.skillVersionId = skillVersionId;
    }

    public String getScannerName() {
        return scannerName;
    }

    public void setScannerName(String scannerName) {
        this.scannerName = scannerName;
    }

    public String getScannerVersion() {
        return scannerVersion;
    }

    public void setScannerVersion(String scannerVersion) {
        this.scannerVersion = scannerVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<Map<String, Object>> getFindingsJson() {
        return findingsJson;
    }

    public void setFindingsJson(List<Map<String, Object>> findingsJson) {
        this.findingsJson = findingsJson;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    /** 转成对外的扫描摘要，findings_json 里的键直接对上响应字段。 */
    public com.skillmesh.api.version.dto.SkillVersionResponse.ScanSummary toSummary() {
        List<com.skillmesh.api.version.dto.SkillVersionResponse.ScanSummary.Finding> items =
            new java.util.ArrayList<>();
        for (Map<String, Object> finding : findingsJson) {
            items.add(new com.skillmesh.api.version.dto.SkillVersionResponse.ScanSummary.Finding(
                String.valueOf(finding.get("path")),
                ((Number) finding.getOrDefault("line", 0)).intValue(),
                String.valueOf(finding.get("rule")),
                String.valueOf(finding.get("detail"))));
        }
        return new com.skillmesh.api.version.dto.SkillVersionResponse.ScanSummary(
            scannerName, scannerVersion, status, summary, completedAt, items);
    }

    public void setCompletedAt(OffsetDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
