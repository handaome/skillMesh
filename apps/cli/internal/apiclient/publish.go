package apiclient

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
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
	// OrganizationID 与 OwnerTeamID 决定可见性范围（团队可见必须两个都带）。
	OrganizationID *string `json:"organization_id,omitempty"`
	OwnerTeamID    *string `json:"owner_team_id,omitempty"`
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
	// ExpectedBaseRevision 是客户端做决策时看到的云端已发布版本内容摘要。
	// 不传 = 服务端不校验基线；传了但云端已经变了，服务端回 409。
	ExpectedBaseRevision string `json:"expected_base_revision,omitempty"`
}

// UploadTarget 是上传目标。服务端返回相对地址，由客户端拼上 API base，
// 内容经 API 中转写进对象存储——客户端不需要能访问 MinIO。
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
	// VersionStatus 是版本的状态（rejected / quarantined / pending_review / published）。
	// 会话自己的 status 只说「上传完了没有」，不说这版被服务端怎么处理了——
	// 拿它去推断发布结果，就会把被扫描拦下的版本说成「已提交审核」。
	VersionStatus string `json:"version_status"`
}

// CreateSkill 建一个 Skill 元数据。
func (c *Client) CreateSkill(ctx context.Context, req CreateSkillRequest) (Skill, error) {
	var out Skill
	if err := c.doWith(ctx, http.MethodPost, "/skills", nil, req, &out); err != nil {
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
	var out CreateVersionResponse
	path := "/skills/" + url.PathEscape(skillID) + "/versions"
	if err := c.doWith(ctx, http.MethodPost, path, nil, req, &out); err != nil {
		return CreateVersionResponse{}, err
	}
	return out, nil
}

// CompleteUpload 告诉服务端上传完了，请校验并转正。
func (c *Client) CompleteUpload(ctx context.Context, sessionID string) (UploadSession, error) {
	var out UploadSession
	path := "/uploads/" + url.PathEscape(sessionID) + "/complete"
	if err := c.doWith(ctx, http.MethodPost, path, nil, map[string]any{}, &out); err != nil {
		return UploadSession{}, err
	}
	return out, nil
}

// PutArtifact 把制品上传到 API 中转端点。
// 服务端返回的是相对地址（/uploads/{id}），拼上 API base；
// 走 API 就要带令牌——直传对象存储时反而不能带。
func (c *Client) PutArtifact(ctx context.Context, target UploadTarget, data []byte) error {
	targetURL := target.URL
	if !strings.HasPrefix(targetURL, "http://") && !strings.HasPrefix(targetURL, "https://") {
		targetURL = c.base + "/" + strings.TrimPrefix(targetURL, "/")
	}
	request, err := http.NewRequestWithContext(ctx, target.Method, targetURL, bytes.NewReader(data))
	if err != nil {
		return err
	}
	for key, value := range target.Headers {
		request.Header.Set(key, value)
	}
	request.Header.Set("Authorization", "Bearer "+c.token)
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

// ReviewVersion 提交审核或给出审核结论（设计文档 §12）。
func (c *Client) ReviewVersion(ctx context.Context, skillID, version, action,
	comment string) (map[string]any, error) {

	body := map[string]string{}
	if comment != "" {
		body["comment"] = comment
	}
	var out map[string]any
	if err := c.doWith(ctx, http.MethodPost,
		"/skills/"+url.PathEscape(skillID)+"/versions/"+url.PathEscape(version)+"/"+action,
		nil, body, &out); err != nil {
		return nil, err
	}
	return out, nil
}
