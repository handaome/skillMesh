package com.skillmesh.api.skill.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 已删除（归档）的 Skill。
 *
 * 客户端靠它判断「云端删过的、本地也要跟着删」：
 * 只有服务端明确记下过删除，本地才敢动——靠「查不到」推断会把
 * 换服务器导致的空库误当成删除。
 */
public record DeletedSkillResponse(
        UUID id,
        String slug,
        String name,
        @JsonProperty("deleted_at") OffsetDateTime deletedAt) {}
