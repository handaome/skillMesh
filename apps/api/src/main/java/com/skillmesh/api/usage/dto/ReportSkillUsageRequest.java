package com.skillmesh.api.usage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 客户端上报的一次 Skill 调用。
 *
 * event_id 是幂等键：客户端重发同一次调用时带上同一个值，服务端只记一条。
 * args 只放参数摘要，不放文件内容。
 */
public record ReportSkillUsageRequest(
        @NotBlank @Size(max = 128) String skill,
        @Size(max = 64) String event_id,
        @Size(max = 512) String project,
        @Size(max = 2000) String args,
        @Size(max = 64) String agent) {
}
