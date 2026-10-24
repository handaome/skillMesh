package com.skillmesh.api.invite.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 邀请列表的一页。
 *
 * 形状跟 SkillPage 一样（items/next_cursor/has_more）：网页端照抄那套游标分页
 * 就能接上，不用为邀请另发明一种翻页协议。
 */
public record InvitationPage(
    List<InvitationSummary> items,
    @JsonProperty("next_cursor") String nextCursor,
    @JsonProperty("has_more") boolean hasMore) {}
