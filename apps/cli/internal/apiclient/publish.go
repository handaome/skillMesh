package apiclient

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
)

// CreateSkillRequest 对应 OpenAPI 的 CreateSkillRequest。
type CreateSkillRequest struct {
	Slug        string         `json:"slug"`
	Name        string         `json:"name"`
	Description string         `json:"description"`
	Visibility  string         `json:"visibility"`
	Tags        []string       `json:"tags,omitempty"`
	Metadata    map[string]any `json:"metadata,omitempty"`
	// Source 是来源元数据（设计文档 §7.1）；本地直接发布的 Skill 记 local_import。
	Source *SkillSource `json:"source,omitempty"`
}

// ManifestEntry 对应 OpenAPI 的 CreateVersionRequest.manifest。
type ManifestEntry struct {
	Path       string `json:"path"`
	SHA256     string `json:"sha256"`
	SizeBytes  int64  `json:"size_bytes"`
	Executable bool   `json:"executable"`
}

// CreateVersionRequest 对应 OpenAPI 的 CreateVersionRequest。
type CreateVersionRequest struct {
	Version      string          `json:"version"`
	Channel      string          `json:"channel"`
	SourceCommit *string         `json:"source_commit,omitempty"`
	Changelog    string          `json:"changelog,omitempty"`
	SizeBytes    int64           `json:"size_bytes"`
	FileCount    int             `json:"file_count"`
	SHA256       string          `json:"sha256"`
	Manifest     []ManifestEntry `json:"manifest"`
}

// UploadTarget 是直传对象存储的目标。注意 url 自带签名，
// 请求它时不能再加 Authorization 头。
type UploadTarget struct {
	SessionID string            `json:"session_id"`
	Method    string            `json:"method"`
	URL       string            `json:"url"`
	Headers   map[string]string `json:"headers"`
	ExpiresAt string            `json:"expires_at"`
}

// CreateVersionResponse 对应 OpenAPI 的 CreateVersionResponse。
type CreateVersionResponse struct {
	VersionID string       `json:"version_id"`
	Status    string       `json:"status"`
	Upload    UploadTarget `json:"upload"`
}

// UploadSession 对应 OpenAPI 的 UploadSession。
type UploadSession struct {
	ID             string `json:"id"`
	Purpose        string `json:"purpose"`
	Status         string `json:"status"`
	VerifiedSHA256 string `json:"verified_sha256"`
	ErrorCode      string `json:"error_code"`
}

// CreateSkill 建一个 Skill 元数据。
func (c *Client) CreateSkill(ctx context.Context, req CreateSkillRequest) (Skill, error) {
	body, err := json.Marshal(req)
	if err != nil {
		return Skill{}, err
	}
	var out Skill
	if err := c.send(ctx, http.MethodPost, "/skills", body, &out); err != nil {
		return Skill{}, err
	}
	return out, nil
}

// FindSkillBySlug 按 slug 精确找 Skill。找不到返回 false。
// 服务端没有 by-slug 接口，只能走搜索再精确匹配——好处是不用改契约。
func (c *Client) FindSkillBySlug(ctx context.Context, slug string) (Skill, bool, error) {
	cursor := ""
	for {
		page, err := c.Search(ctx, slug, "", "", false, 100, cursor)
		if err != nil {
			return Skill{}, false, err
		}
		for _, skill := range page.Items {
			if skill.Slug == slug {
				return skill, true, nil
			}
		}
		if !page.HasMore || page.NextCursor == nil {
			return Skill{}, false, nil
		}
		cursor = *page.NextCursor
	}
}

// CreateVersion 建一个候选版本，返回直传对象存储的目标。
func (c *Client) CreateVersion(ctx context.Context, skillID string, req CreateVersionRequest) (CreateVersionResponse, error) {
	body, err := json.Marshal(req)
	if err != nil {
		return CreateVersionResponse{}, err
	}
	var out CreateVersionResponse
	path := "/skills/" + url.PathEscape(skillID) + "/versions"
	if err := c.send(ctx, http.MethodPost, path, body, &out); err != nil {
		return CreateVersionResponse{}, err
	}
	return out, nil
}

// CompleteUpload 告诉服务端上传完了，请校验并转正。
func (c *Client) CompleteUpload(ctx context.Context, sessionID string) (UploadSession, error) {
	var out UploadSession
	path := "/uploads/" + url.PathEscape(sessionID) + "/complete"
	if err := c.send(ctx, http.MethodPost, path, []byte("{}"), &out); err != nil {
		return UploadSession{}, err
	}
	return out, nil
}

// PutArtifact 把制品直传到对象存储的签名地址。
// 签名地址自带凭据，这里绝不能再带 Authorization 头，否则签名校验失败。
func (c *Client) PutArtifact(ctx context.Context, target UploadTarget, data []byte) error {
	request, err := http.NewRequestWithContext(ctx, target.Method, target.URL, bytes.NewReader(data))
	if err != nil {
		return err
	}
	for key, value := range target.Headers {
		request.Header.Set(key, value)
	}
	response, err := c.http.Do(request)
	if err != nil {
		return fmt.Errorf("上传制品失败: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		body, _ := io.ReadAll(response.Body)
		return fmt.Errorf("上传制品失败（HTTP %d）：%s", response.StatusCode, string(body))
	}
	return nil
}

// send 发一个带 JSON body 的请求。
func (c *Client) send(ctx context.Context, method, path string, body []byte, out any) error {
	target := c.base + path
	request, err := http.NewRequestWithContext(ctx, method, target, bytes.NewReader(body))
	if err != nil {
		return err
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Content-Type", "application/json")
	if c.token != "" {
		request.Header.Set("Authorization", "Bearer "+c.token)
	}
	response, err := c.http.Do(request)
	if err != nil {
		return fmt.Errorf("请求 %s 失败: %w", target, err)
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return decodeProblem(response)
	}
	if out == nil {
		_, _ = io.Copy(io.Discard, response.Body)
		return nil
	}
	return json.NewDecoder(response.Body).Decode(out)
}

var _ = strconv.Itoa

// ReviewVersion 提交审核或给出审核结论（设计文档 §12）。
func (c *Client) ReviewVersion(ctx context.Context, skillID, version, action,
	comment string) (map[string]any, error) {

	body := map[string]string{}
	if comment != "" {
		body["comment"] = comment
	}
	var out map[string]any
	if err := c.doWith(ctx, http.MethodPost,
		"/skills/"+skillID+"/versions/"+version+"/"+action, nil, body, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// NeedsReview 问服务端这个 Skill 的新版本要不要走审核。
// 猜错的后果是误导用户说「已发布」，所以这里问一句而不是客户端自己判断。
func (c *Client) NeedsReview(ctx context.Context, skillID string) (bool, error) {
	skill, err := c.Skill(ctx, skillID)
	if err != nil {
		return false, err
	}
	return skill.Visibility == "team" || skill.Visibility == "organization", nil
}
