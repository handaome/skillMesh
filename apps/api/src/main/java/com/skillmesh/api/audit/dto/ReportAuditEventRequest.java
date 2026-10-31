package com.skillmesh.api.audit.dto;

import com.skillmesh.api.audit.AuditOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * 客户端上报的审计事件。
 *
 * details 只放业务事实（版本号、失败原因），不放令牌和文件内容。
 */
public record ReportAuditEventRequest(
        @NotBlank @Size(max = 64) String action,
        @Size(max = 64) String target_type,
        @Size(max = 128) String target_id,
        AuditOutcome outcome,
        Map<String, Object> details) {}
