package com.skillmesh.api.audit;

import com.skillmesh.api.skill.VisibleSkills;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditRepository extends JpaRepository<AuditEventEntity, Long> {

    List<AuditEventEntity> findByTargetTypeAndTargetIdOrderByOccurredAtDesc(
        String targetType, String targetId, Pageable pageable);

    /**
     * 我可见的 Skill 的审计流水。
     *
     * 可见性条件与搜索共用 {@link VisibleSkills}——权限判断只有一份。
     * 以前是「把全部 Skill 查出来逐个判可见、再逐个查事件」，
     * 那等于把权限过滤放在内存里，且事件数随 Skill 数线性涨。
     */
    @Query(value = """
        select e.* from audit_events e
        join skills s on s.id::text = e.target_id
        where e.target_type = 'skill'
          and """ + VisibleSkills.WHERE + """
          and (cast(:action as text) is null or e.action = :action)
        order by e.occurred_at desc, e.id desc
        limit cast(:limit as int)
        """, nativeQuery = true)
    List<AuditEventEntity> findVisibleForUser(@Param("owner") UUID owner,
                                              @Param("action") String action,
                                              @Param("limit") int limit);
}
