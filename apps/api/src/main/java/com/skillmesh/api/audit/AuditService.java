package com.skillmesh.api.audit;

import com.skillmesh.api.auth.CurrentUser;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计流水（详细设计 §10 审计）。
 *
 * 写审计失败不该把业务动作一起回滚——动作已经发生了，
 * 记不上是另一回事（设计文档说的「不阻断主流程」）。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository repository;

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(CurrentUser actor, String action, String targetType, Object targetId,
                       String outcome, Map<String, Object> details) {
        try {
            AuditEventEntity event = new AuditEventEntity();
            event.setOccurredAt(OffsetDateTime.now(ZoneOffset.UTC));
            event.setActorUserId(actor == null ? null : actor.id());
            event.setAction(action);
            event.setTargetType(targetType);
            event.setTargetId(targetId == null ? null : targetId.toString());
            event.setOutcome(outcome);
            // details 里只放业务事实，不放令牌、私钥、Skill 正文。
            event.setDetailsJson(details == null ? Map.of() : details);
            repository.save(event);
        } catch (RuntimeException e) {
            log.error("审计写入失败 action={} target={}/{} —— 业务动作照常生效",
                action, targetType, targetId, e);
        }
    }

    @Transactional(readOnly = true)
    public List<AuditEventEntity> recentForSkill(UUID skillId, int limit) {
        return repository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc(
            "skill", skillId.toString(), PageRequest.of(0, limit));
    }
}
