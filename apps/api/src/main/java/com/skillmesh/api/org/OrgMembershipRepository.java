package com.skillmesh.api.org;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrgMembershipRepository
    extends JpaRepository<MembershipEntities.OrgMembership, MembershipEntities.OrgKey> {

    List<MembershipEntities.OrgMembership> findByIdUserId(UUID userId);

    List<MembershipEntities.OrgMembership> findByIdOrganizationId(UUID organizationId);
}
