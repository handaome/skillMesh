package com.skillmesh.api.review;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<ReviewEntity, UUID> {

    List<ReviewEntity> findBySkillVersionIdOrderByCreatedAtAsc(UUID skillVersionId);

    /** 这个人是不是已经对这个版本做过审核（表上有 UNIQUE(skill_version_id, reviewer_id)）。 */
    boolean existsBySkillVersionIdAndReviewerId(UUID skillVersionId, UUID reviewerId);

    /**
     * 这个人是不是审过这个 Skill 的某个版本。
     *
     * 审过的人有权看它的审计流水（§2.4 权限矩阵「查看组织审计」一行）：
     * 自己批过的那一版后来被谁撤了、为什么撤，他要看得见。
     */
    @Query("select count(r) > 0 from ReviewEntity r, SkillVersionEntity v "
        + "where r.skillVersionId = v.id and r.reviewerId = :reviewerId and v.skillId = :skillId")
    boolean existsByReviewerIdAndSkillId(@Param("reviewerId") UUID reviewerId,
                                         @Param("skillId") UUID skillId);
}
