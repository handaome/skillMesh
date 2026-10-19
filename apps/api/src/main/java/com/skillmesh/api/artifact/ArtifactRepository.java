package com.skillmesh.api.artifact;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtifactRepository extends JpaRepository<ArtifactEntity, UUID> {

    Optional<ArtifactEntity> findBySha256(String sha256);
}
