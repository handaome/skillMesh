package com.skillmesh.api.audit;

import com.skillmesh.api.skill.VisibleSkills;
import java.time.OffsetDateTime;
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
          and (cast(:cursorTime as timestamptz) is null
               or (e.occurred_at, e.id) < (cast(:cursorTime as timestamptz),
                                          cast(:cursorId as bigint)))
        order by e.occurred_at desc, e.id desc
        limit cast(:limit as int)
        """, nativeQuery = true)
    List<AuditEventEntity> findVisibleForUser(@Param("owner") UUID owner,
                                              @Param("action") String action,
                                              @Param("cursorTime") OffsetDateTime cursorTime,
                                              @Param("cursorId") Long cursorId,
                                              @Param("limit") int limit);

    /**
     * 某个 Skill 的流水，带游标翻页。
     *
     * 游标是 (occurred_at, id)：时间只到微秒，同一瞬间写下的两条事件
     * 顺序不定，少了 id 那一半，翻页会漏掉或重复。
     */
    @Query(value = """
        select e.* from audit_events e
        where e.target_type = :targetType
          and e.target_id = :targetId
          and (cast(:cursorTime as timestamptz) is null
               or (e.occurred_at, e.id) < (cast(:cursorTime as timestamptz),
                                          cast(:cursorId as bigint)))
        order by e.occurred_at desc, e.id desc
        """, nativeQuery = true)
    List<AuditEventEntity> findForTargetAfterCursor(@Param("targetType") String targetType,
                                                     @Param("targetId") String targetId,
                                                     @Param("cursorTime") OffsetDateTime cursorTime,
                                                     @Param("cursorId") Long cursorId,
                                                     Pageable pageable);
}
