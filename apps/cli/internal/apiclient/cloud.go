package apiclient

import (
	"context"
	"net/http"
	"net/url"
)

// DeletedSkill 是云端删掉（归档）的一条记录。
//
// 客户端靠它判断本地哪些要跟着删：只有服务端明确记下过删除，
// 本地才敢动——靠「云端查不到」推断，会把换服务器导致的空库误当成删除。
type DeletedSkill struct {
	ID        string `json:"id"`
	Slug      string `json:"slug"`
	Name      string `json:"name"`
	DeletedAt string `json:"deleted_at"`
}

// DeletedSkillPage 是已删除 Skill 的一页。
type DeletedSkillPage struct {
	Items      []DeletedSkill `json:"items"`
	NextCursor *string        `json:"next_cursor"`
	HasMore    bool           `json:"has_more"`
}

// skillsPath 是 Skill 资源的路径前缀。
const skillsPath = "/skills/"

// ArchiveSkill 删除云端的 Skill。
//
// 软删除：数据留着，回收期内能还原（ArchiveSkill 的逆操作是 RestoreSkill）。
func (c *Client) ArchiveSkill(ctx context.Context, skillID string) error {
	return c.doWith(ctx, http.MethodDelete,
		skillsPath+url.PathEscape(skillID), nil, nil, nil)
}

// RestoreSkill 还原被删除的 Skill。
func (c *Client) RestoreSkill(ctx context.Context, skillID string) (Skill, error) {
	var out Skill
	path := skillsPath + url.PathEscape(skillID) + "/restore"
	if err := c.doWith(ctx, http.MethodPost, path, nil, nil, &out); err != nil {
		return Skill{}, err
	}
	return out, nil
}

// RevokeVersion 撤回一个版本。
//
// 撤回不可逆：撤回的版本不能再被安装，也回不到已发布，要恢复只能发新版本。
// 制品与文件都留在云端，只是不再对外可用。
func (c *Client) RevokeVersion(ctx context.Context, skillID, version, reason string) (map[string]any, error) {
	body := map[string]string{}
	if reason != "" {
		body["reason"] = reason
	}
	var out map[string]any
	path := skillsPath + url.PathEscape(skillID) + "/versions/" + url.PathEscape(version) + "/revoke"
	if err := c.doWith(ctx, http.MethodPost, path, nil, body, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// DeletedSkills 列出云端删掉（归档）的 Skill，翻完所有页。
func (c *Client) DeletedSkills(ctx context.Context) ([]DeletedSkill, error) {
	var all []DeletedSkill
	cursor := ""
	for {
		query := url.Values{}
		query.Set("limit", "100")
		if cursor != "" {
			query.Set("cursor", cursor)
		}
		var page DeletedSkillPage
		if err := c.do(ctx, http.MethodGet, "/deleted-skills", query, &page); err != nil {
			return nil, err
		}
		all = append(all, page.Items...)
		if !page.HasMore || page.NextCursor == nil {
			return all, nil
		}
		cursor = *page.NextCursor
	}
}
