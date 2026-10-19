package com.skillmesh.api.skill;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceRepository extends JpaRepository<SourceEntity, UUID> {

    List<SourceEntity> findBySkillIdOrderByCreatedAtDesc(UUID skillId);
}
