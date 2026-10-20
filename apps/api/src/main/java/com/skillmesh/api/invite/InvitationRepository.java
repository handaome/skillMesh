package com.skillmesh.api.invite;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvitationRepository extends JpaRepository<InvitationEntity, UUID> {

    Optional<InvitationEntity> findByTokenHash(String tokenHash);

    List<InvitationEntity> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);
}
