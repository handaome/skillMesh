package com.skillmesh.api.review;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<ReviewEntity, UUID> {

    List<ReviewEntity> findBySkillVersionIdOrderByCreatedAtAsc(UUID skillVersionId);

    /** 这个人是不是已经对这个版本做过审核（表上有 UNIQUE(skill_version_id, reviewer_id)）。 */
    boolean existsBySkillVersionIdAndReviewerId(UUID skillVersionId, UUID reviewerId);
}
