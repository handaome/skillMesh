package com.skillmesh.api.skill;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceRepository extends JpaRepository<SourceEntity, UUID> {

    List<SourceEntity> findBySkillIdOrderByCreatedAtDesc(UUID skillId);

    /**
     * 一批 Skill 的来源。
     *
     * 列表页要显示每个 Skill 的仓库地址。一次查完，别一个一个查——
     * 列表有几十条时那点往返就拖垮页面了。
     */
    List<SourceEntity> findBySkillIdInOrderByCreatedAtDesc(List<UUID> skillIds);
}
