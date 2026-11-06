package apiclient

import (
	"context"
	"net/http"
	"net/url"
	"strconv"
)

// SkillUsageInput 是上报一次 Skill 调用要带的字段。
//
// EventID 是幂等键：同一次调用重发多次，服务端只记一条。
type SkillUsageInput struct {
	Skill   string `json:"skill"`
	EventID string `json:"event_id"`
	Project string `json:"project,omitempty"`
	Args    string `json:"args,omitempty"`
	Agent   string `json:"agent,omitempty"`
}

// ReportSkillUsage 上报一次 Skill 调用。
//
// 不看响应体：服务端回 201 {"accepted":true|false}，
// accepted=false 只是说这条已经记过（幂等键撞上了），不算失败。
func (c *Client) ReportSkillUsage(ctx context.Context, in SkillUsageInput) error {
	return c.doWith(ctx, http.MethodPost, "/skill-usage/events", nil, in, nil)
}

// SkillUsageStat 是一个 Skill 的聚合统计。
type SkillUsageStat struct {
	Skill      string `json:"skill"`
	CallCount  int64  `json:"call_count"`
	UserCount  int64  `json:"user_count"`
	LastUsedAt string `json:"last_used_at"`
}

// statsLimit 是统计端点一次最多返回的条数，与服务端默认值一致。
const statsLimit = 50

// SkillUsageStats 查调用统计。days 为 0 时由服务端取默认窗口。
func (c *Client) SkillUsageStats(ctx context.Context, days int, skill string) ([]SkillUsageStat, error) {
	query := url.Values{}
	if days > 0 {
		query.Set("days", strconv.Itoa(days))
	}
	if skill != "" {
		query.Set("skill", skill)
	}
	query.Set("limit", strconv.Itoa(statsLimit))
	var out []SkillUsageStat
	if err := c.doWith(ctx, http.MethodGet, "/skill-usage/stats", query, nil, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// SkillUsageEvent 是一次调用的流水。
type SkillUsageEvent struct {
	OccurredAt  string `json:"occurred_at"`
	Skill       string `json:"skill"`
	Project     string `json:"project"`
	ArgsPreview string `json:"args_preview"`
	Agent       string `json:"agent"`
}

// SkillUsagePage 是流水的一页：数据 + 下一页游标 + 还有没有更多。
type SkillUsagePage struct {
	Items      []SkillUsageEvent `json:"items"`
	NextCursor *string           `json:"next_cursor"`
	HasMore    bool              `json:"has_more"`
}

// SkillUsageEvents 查调用流水。cursor 为空时从头开始。
func (c *Client) SkillUsageEvents(ctx context.Context, limit int, cursor string) (SkillUsagePage, error) {
	query := url.Values{}
	if limit > 0 {
		query.Set("limit", strconv.Itoa(limit))
	}
	if cursor != "" {
		query.Set("cursor", cursor)
	}
	var out SkillUsagePage
	if err := c.doWith(ctx, http.MethodGet, "/skill-usage/events", query, nil, &out); err != nil {
		return out, err
	}
	return out, nil
}
