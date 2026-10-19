package com.skillmesh.api.version;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillVersionRepository extends JpaRepository<SkillVersionEntity, UUID> {

    boolean existsBySkillIdAndVersion(UUID skillId, String version);

    Optional<SkillVersionEntity> findBySkillIdAndVersion(UUID skillId, String version);

    List<SkillVersionEntity> findBySkillIdOrderByCreatedAtDesc(UUID skillId);

    /**
     * 按制品反查版本。同一份内容可以被多个版本引用（内容寻址，sha256 唯一），
     * 所以这里是一对多，不能用 Optional。
     */
    List<SkillVersionEntity> findByArtifactId(UUID artifactId);
}
