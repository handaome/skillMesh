package apiclient

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
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
//
// idempotencyKey 是这次创建的幂等键：断网重试时必须复用同一个，
// 不然服务端会建出第二个同名 Skill（详细设计 §2.1、§4.3 API-001）。
func (c *Client) CreateSkill(ctx context.Context, req CreateSkillRequest,
	idempotencyKey string) (Skill, error) {
	var out Skill
	if err := c.doWith(ctx, http.MethodPost, "/skills", nil, req, &out,
		idempotencyHeaders(idempotencyKey)); err != nil {
		return Skill{}, err
	}
	return out, nil
}

// IdempotencyKey 按「作用域 + 内容摘要 + 请求体」算幂等键。
//
// 服务端按「同一个键 = 同一次请求」处理：重试时把上次的响应原样还回来，
// 不再建一份。
//
// 键必须是请求内容的函数：同一个键配了不同的内容，服务端会拒（409）。
// 内容摘要也要算进去：建 Skill 的请求体只有元数据，同样的元数据
// 可能对应两次不同的发布（内容变了）。
func IdempotencyKey(scope, contentDigest string, body any) string {
	raw, err := json.Marshal(body)
	if err != nil {
		return ""
	}
	sum := sha256.Sum256([]byte(scope + "|" + contentDigest + "|" + string(raw)))
	return scope + "-" + hex.EncodeToString(sum[:])[:32]
}

// idempotencyHeaders 把幂等键变成请求头。没键就一个头都不带。
func idempotencyHeaders(key string) map[string]string {
	if key == "" {
		return nil
	}
	return map[string]string{"Idempotency-Key": key}
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
//
// idempotencyKey 是这次创建的幂等键：断网重试时必须复用同一个，
// 不然服务端会多出一个版本（详细设计 §2.1、§4.3 API-001）。
func (c *Client) CreateVersion(ctx context.Context, skillID string, req CreateVersionRequest,
	idempotencyKey string) (CreateVersionResponse, error) {
	var out CreateVersionResponse
	path := "/skills/" + url.PathEscape(skillID) + "/versions"
	if err := c.doWith(ctx, http.MethodPost, path, nil, req, &out,
		idempotencyHeaders(idempotencyKey)); err != nil {
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
//
// 上传也是写请求，批量同步时一样会撞限流：跟 doWith 一样按 Retry-After 退避。
func (c *Client) PutArtifact(ctx context.Context, target UploadTarget, data []byte) error {
	for attempt := 0; ; attempt++ {
		retry, err := c.putArtifactOnce(ctx, target, data)
		if err == nil {
			return nil
		}
		if !retry || attempt >= maxRateLimitRetries {
			return err
		}
	}
}

func (c *Client) putArtifactOnce(ctx context.Context, target UploadTarget,
	data []byte) (bool, error) {

	targetURL := target.URL
	if !strings.HasPrefix(targetURL, "http://") && !strings.HasPrefix(targetURL, "https://") {
		targetURL = c.base + "/" + strings.TrimPrefix(targetURL, "/")
	}
	request, err := http.NewRequestWithContext(ctx, target.Method, targetURL, bytes.NewReader(data))
	if err != nil {
		return false, err
	}
	for key, value := range target.Headers {
		request.Header.Set(key, value)
	}
	request.Header.Set("Authorization", bearerPrefix+c.token)
	response, err := c.http.Do(request)
	if err != nil {
		return false, fmt.Errorf("上传制品失败: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusTooManyRequests {
		wait := rateLimitWait(response)
		if err := sleepContext(ctx, wait); err != nil {
			return false, err
		}
		return true, fmt.Errorf("上传被限流，%s 后重试", wait)
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		body, _ := io.ReadAll(response.Body)
		if response.StatusCode == http.StatusRequestEntityTooLarge {
			// 反向代理挡在前面，请求体没到服务端。nginx 的 client_max_body_size
			// 默认只有 1m，Skill 包稍大一点就撞上，报的还是代理自己的 HTML 错误页。
			return false, fmt.Errorf("上传制品失败（HTTP 413）：本次包 %d 字节，超过服务端上限"+
				"（反向代理的 client_max_body_size），调大后重试", len(data))
		}
		return false, fmt.Errorf("上传制品失败（HTTP %d）：%s",
			response.StatusCode, briefBody(body))
	}
	_, _ = io.Copy(io.Discard, response.Body)
	return false, nil
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
