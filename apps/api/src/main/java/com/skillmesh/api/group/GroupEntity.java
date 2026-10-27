package com.skillmesh.api.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 技能集合：命名的一组 Skill。
 *
 * 成员存在 member_ids 里，不另开成员表：成员关系没有别的属性，
 * 集合通常也只有几个成员。
 */
@Entity
@Table(name = "skill_groups")
public class GroupEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "owner_team_id")
    private UUID ownerTeamId;

    @Column(nullable = false)
    private String visibility;

    @Column(nullable = false)
    private String status;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "member_ids", columnDefinition = "uuid[]", nullable = false)
    private UUID[] memberIds;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(UUID ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(UUID organizationId) {
        this.organizationId = organizationId;
    }

    public UUID getOwnerTeamId() {
        return ownerTeamId;
    }

    public void setOwnerTeamId(UUID ownerTeamId) {
        this.ownerTeamId = ownerTeamId;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID[] getMemberIds() {
        return memberIds;
    }

    public void setMemberIds(UUID[] memberIds) {
        this.memberIds = memberIds;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
