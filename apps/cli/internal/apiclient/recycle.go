package apiclient

import (
	"context"
	"net/http"
	"net/url"
)

// RecycleFile 是云端回收站里的一个文件（设计文档 §6.4）。
type RecycleFile struct {
	Path          string `json:"path"`
	SHA256        string `json:"sha256"`
	SourceVersion string `json:"source_version"`
	DeletedAt     string `json:"deleted_at"`
	ExpiresAt     string `json:"expires_at"`
}

// RestoredFile 是恢复一个文件的结果。
type RestoredFile struct {
	SkillID string `json:"skill_id"`
	Version string `json:"version"`
	Status  string `json:"status"`
}

// RecycleFiles 列出一个 Skill 回收站里的文件。
func (c *Client) RecycleFiles(ctx context.Context, skillID string) ([]RecycleFile, error) {
	var out []RecycleFile
	path := "/skills/" + url.PathEscape(skillID) + "/recycle"
	if err := c.doWith(ctx, http.MethodGet, path, nil, nil, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// RestoreFile 把回收站里的一个文件放回云端，产出新版本。
func (c *Client) RestoreFile(ctx context.Context, skillID, filePath string) (RestoredFile, error) {
	var out RestoredFile
	path := "/skills/" + url.PathEscape(skillID) + "/recycle/restore"
	body := map[string]string{"path": filePath}
	if err := c.doWith(ctx, http.MethodPost, path, nil, body, &out); err != nil {
		return RestoredFile{}, err
	}
	return out, nil
}
