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
     *
     * join 条件 {@code s.id::text = e.target_id} 靠 V7 迁移加的表达式索引
     * {@code skills ((id::text))} 才走得动：skills.id 是 uuid，转成 text 之后
     * 主键索引用不上。没有那个索引，planner 只能把整张 skills 扫一遍。
     *
     * 转换留在 uuid 那一边是有原因的：target_id 是 text，别的 target_type
     * 装的是任意字符串，转 uuid 会在非法的行上报错（invalid input syntax）。
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
