package com.skillmesh.api.org;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamMembershipRepository
    extends JpaRepository<MembershipEntities.TeamMembership, MembershipEntities.TeamKey> {

    List<MembershipEntities.TeamMembership> findByIdUserId(UUID userId);

    List<MembershipEntities.TeamMembership> findByIdTeamId(UUID teamId);
}
