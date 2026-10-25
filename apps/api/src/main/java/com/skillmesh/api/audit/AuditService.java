package com.skillmesh.api.audit;

import com.skillmesh.api.auth.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
            applyRequestContext(event);
            repository.save(event);
        } catch (RuntimeException e) {
            log.error("审计写入失败 action={} target={}/{} —— 业务动作照常生效",
                action, targetType, targetId, e);
        }
    }

    /**
     * 把当前请求的痕迹补到审计行里。
     *
     * 这些是「请求属性」不是「业务事实」——每个调用点自己传是重复劳动，
     * 漏传一个就少一列。从当前请求直接取，漏不了。
     */
    private void applyRequestContext(AuditEventEntity event) {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return;
        }
        String requestId = request.getHeader("X-Request-Id");
        event.setRequestId(requestId != null && !requestId.isBlank()
            ? requestId : "req_" + UUID.randomUUID().toString().replace("-", ""));
        event.setIpAddress(clientIp(request));
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && !userAgent.isBlank()) {
            event.setUserAgent(userAgent.length() > 512 ? userAgent.substring(0, 512) : userAgent);
        }
    }

    /** 当前请求。没在 HTTP 线程里（比如后台任务）时返回 null。 */
    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            return servlet.getRequest();
        }
        return null;
    }

    /**
     * 取真实来源 IP。
     *
     * 前面挂了反向代理时，remoteAddr 是代理的地址——X-Forwarded-For 里
     * 才是原 client。取第一个（最靠客户端的那个）。
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first.length() > 64 ? first.substring(0, 64) : first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? null : remote;
    }

    @Transactional(readOnly = true)
    public List<AuditEventEntity> recentForSkill(UUID skillId, int limit) {
        return repository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc(
            "skill", skillId.toString(), PageRequest.of(0, limit));
    }

    /**
     * 我可见的 Skill 的流水。
     *
     * 可见性在 SQL 里判（与搜索共用同一份条件），不在内存里筛。
     */
    @Transactional(readOnly = true)
    public List<AuditEventEntity> recentVisible(CurrentUser user, String action, int limit) {
        String filter = action == null || action.isBlank() ? null : action;
        return repository.findVisibleForUser(user.id(), filter, Math.clamp(limit, 1, 200));
    }
}
