package com.skillmesh.api.version;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillVersionRepository extends JpaRepository<SkillVersionEntity, UUID> {

    boolean existsBySkillIdAndVersion(UUID skillId, String version);

    Optional<SkillVersionEntity> findBySkillIdAndVersion(UUID skillId, String version);

    List<SkillVersionEntity> findBySkillIdOrderByCreatedAtDesc(UUID skillId);

    Optional<SkillVersionEntity> findByArtifactId(UUID artifactId);
}
