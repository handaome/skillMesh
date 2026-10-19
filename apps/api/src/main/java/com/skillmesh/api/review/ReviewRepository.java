package com.skillmesh.api.review;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<ReviewEntity, UUID> {

    List<ReviewEntity> findBySkillVersionIdOrderByCreatedAtAsc(UUID skillVersionId);
}
