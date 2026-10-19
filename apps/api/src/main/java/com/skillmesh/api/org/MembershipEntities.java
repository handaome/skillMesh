package com.skillmesh.api.org;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 两张成员表：结构一样，但主键列名不同（组织成员挂 organization_id，
 * 团队成员挂 team_id），所以主键类型也得分开。
 */
final class MembershipEntities {

    private MembershipEntities() {}

    /** 组织成员。 */
    @Entity
    @Table(name = "organization_memberships")
    public static class OrgMembership {

        @EmbeddedId
        private OrgKey id;

        @Column(nullable = false)
        private String role;

        @Column(name = "joined_at", nullable = false)
        private OffsetDateTime joinedAt;

        public OrgKey getId() {
            return id;
        }

        public void setId(OrgKey id) {
            this.id = id;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public void setJoinedAt(OffsetDateTime joinedAt) {
            this.joinedAt = joinedAt;
        }
    }

    /** 团队成员。 */
    @Entity
    @Table(name = "team_memberships")
    public static class TeamMembership {

        @EmbeddedId
        private TeamKey id;

        @Column(nullable = false)
        private String role;

        @Column(name = "joined_at", nullable = false)
        private OffsetDateTime joinedAt;

        public TeamKey getId() {
            return id;
        }

        public void setId(TeamKey id) {
            this.id = id;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public void setJoinedAt(OffsetDateTime joinedAt) {
            this.joinedAt = joinedAt;
        }
    }

    /** 组织成员主键：组织 + 用户。 */
    @Embeddable
    public static class OrgKey implements Serializable {

        @Column(name = "organization_id", nullable = false)
        private UUID organizationId;

        @Column(name = "user_id", nullable = false)
        private UUID userId;

        public OrgKey() {}

        public OrgKey(UUID organizationId, UUID userId) {
            this.organizationId = organizationId;
            this.userId = userId;
        }

        public UUID getOrganizationId() {
            return organizationId;
        }

        public UUID getUserId() {
            return userId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof OrgKey key
                && Objects.equals(organizationId, key.organizationId)
                && Objects.equals(userId, key.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(organizationId, userId);
        }
    }

    /** 团队成员主键：团队 + 用户。 */
    @Embeddable
    public static class TeamKey implements Serializable {

        @Column(name = "team_id", nullable = false)
        private UUID teamId;

        @Column(name = "user_id", nullable = false)
        private UUID userId;

        public TeamKey() {}

        public TeamKey(UUID teamId, UUID userId) {
            this.teamId = teamId;
            this.userId = userId;
        }

        public UUID getTeamId() {
            return teamId;
        }

        public UUID getUserId() {
            return userId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TeamKey key
                && Objects.equals(teamId, key.teamId)
                && Objects.equals(userId, key.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(teamId, userId);
        }
    }
}
