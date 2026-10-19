package com.skillmesh.api.org;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<TeamEntity, UUID> {

    Optional<TeamEntity> findByOrganizationIdAndSlug(UUID organizationId, String slug);

    List<TeamEntity> findByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);
}
