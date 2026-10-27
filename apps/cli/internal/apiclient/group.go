package apiclient

import (
	"context"
	"net/http"
	"net/url"
)

// Group 是技能集合：命名的一组 Skill。
type Group struct {
	ID          string `json:"id"`
	Slug        string `json:"slug"`
	Name        string `json:"name"`
	Description string `json:"description"`
	Visibility  string `json:"visibility"`
	// MemberCount 是集合里的 Skill 数，InstalledCount 是我装了几个。
	MemberCount    int           `json:"member_count"`
	InstalledCount int           `json:"installed_count"`
	Members        []GroupMember `json:"members"`
}

// GroupMember 是集合里的一个 Skill。
type GroupMember struct {
	ID            string `json:"id"`
	Slug          string `json:"slug"`
	Name          string `json:"name"`
	LatestVersion string `json:"latest_version"`
	Installed     bool   `json:"installed"`
}

// GroupCreate 是建集合的请求体。
type GroupCreate struct {
	Slug           string   `json:"slug"`
	Name           string   `json:"name"`
	Description    string   `json:"description,omitempty"`
	Visibility     string   `json:"visibility"`
	OrganizationID string   `json:"organization_id,omitempty"`
	TeamID         string   `json:"team_id,omitempty"`
	SkillIDs       []string `json:"skill_ids,omitempty"`
}

// Groups 列出我可见的集合。
func (c *Client) Groups(ctx context.Context) ([]Group, error) {
	var out []Group
	return out, c.do(ctx, http.MethodGet, "/groups", nil, &out)
}

// GroupBySlug 按 slug 取一个集合。
func (c *Client) GroupBySlug(ctx context.Context, slug string) (Group, error) {
	var out Group
	return out, c.do(ctx, http.MethodGet, "/groups/slug/"+url.PathEscape(slug), nil, &out)
}

// CreateGroup 建一个集合。
func (c *Client) CreateGroup(ctx context.Context, body GroupCreate) (Group, error) {
	var out Group
	return out, c.doWith(ctx, http.MethodPost, "/groups", nil, body, &out)
}
