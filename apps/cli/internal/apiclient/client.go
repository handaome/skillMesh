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
	"strings"
	"time"
)

type Client struct {
	base  string
	token string
	http  *http.Client
}

func New(baseURL, token string, timeout time.Duration) *Client {
	if timeout == 0 {
		timeout = 30 * time.Second
	}
	return &Client{
		base:  strings.TrimRight(baseURL, "/"),
		token: token,
		http:  &http.Client{Timeout: timeout},
	}
}

func (c *Client) do(ctx context.Context, method, path string, query url.Values, out any) error {
	return c.doWith(ctx, method, path, query, nil, out)
}

// doWith 发一个带请求体和自定义头的请求。headers 一律是键值对，调用方自己拼。
func (c *Client) doWith(ctx context.Context, method, path string, query url.Values,
	body any, out any, headers ...map[string]string) error {

	// 批量操作（一次同步十几个 Skill）本来就会撞上写接口的频率上限，
	// 撞上了就按服务端给的时间退避重试，而不是直接失败。
	for attempt := 0; ; attempt++ {
		retry, err := c.doOnce(ctx, method, path, query, body, out, headers...)
		if err == nil {
			return nil
		}
		if !retry || attempt >= maxRateLimitRetries {
			return err
		}
	}
}

// maxRateLimitRetries 是被限流后最多重试几次。
const maxRateLimitRetries = 3

// doOnce 发一次请求。第二个返回值表示「被限流了，可以退避重试」。
func (c *Client) doOnce(ctx context.Context, method, path string, query url.Values,
	body any, out any, headers ...map[string]string) (bool, error) {

	target := c.base + path
	if len(query) > 0 {
		target += "?" + query.Encode()
	}
	var reader io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			return false, err
		}
		reader = bytes.NewReader(raw)
	}
	req, err := http.NewRequestWithContext(ctx, method, target, reader)
	if err != nil {
		return false, err
	}
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	for _, extra := range headers {
		for key, value := range extra {
			req.Header.Set(key, value)
		}
	}
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return false, &NetworkError{URL: target, Err: err}
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusTooManyRequests {
		wait := rateLimitWait(resp)
		if err := sleepContext(ctx, wait); err != nil {
			return false, err
		}
		// 不能在这里返回 nil 错误：doWith 一看 err == nil 就当成功，
		// 把根本没填过的响应体当结果用（搜索会以为 Skill 不存在，
		// 版本列表会当成空列表）。返回错误让它退避重试，
		// 重试用完了才把错误抛给调用方。
		return true, fmt.Errorf("被限流（HTTP %d），%s 后重试", resp.StatusCode, wait)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return false, decodeProblem(resp)
	}
	if out == nil {
		_, _ = io.Copy(io.Discard, resp.Body)
		return false, nil
	}
	return false, json.NewDecoder(resp.Body).Decode(out)
}

// rateLimitWait 读服务端的 Retry-After，没有就按固定节奏退避。
func rateLimitWait(resp *http.Response) time.Duration {
	if seconds, err := strconv.Atoi(resp.Header.Get("Retry-After")); err == nil && seconds > 0 {
		return time.Duration(seconds) * time.Second
	}
	return 5 * time.Second
}

func sleepContext(ctx context.Context, d time.Duration) error {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

func decodeProblem(resp *http.Response) error {
	raw, _ := io.ReadAll(resp.Body)
	var p Problem
	if err := json.Unmarshal(raw, &p); err != nil || p.Code == "" {
		return &ProblemError{
			HTTPStatus: resp.StatusCode,
			Problem: Problem{
				Code:   "HTTP_" + strconv.Itoa(resp.StatusCode),
				Status: resp.StatusCode,
				Detail: strings.TrimSpace(string(raw)),
			},
		}
	}
	return &ProblemError{HTTPStatus: resp.StatusCode, Problem: p}
}

func (c *Client) Me(ctx context.Context) (MeResponse, error) {
	var out MeResponse
	return out, c.do(ctx, http.MethodGet, "/me", nil, &out)
}

func (c *Client) Search(ctx context.Context, q, visibility, tag string, mine bool,
	limit int, cursor string) (SkillPage, error) {
	query := url.Values{}
	if q != "" {
		query.Set("q", q)
	}
	if visibility != "" {
		query.Set("visibility", visibility)
	}
	if tag != "" {
		query.Set("tag", tag)
	}
	if mine {
		query.Set("mine", "true")
	}
	if limit > 0 {
		query.Set("limit", strconv.Itoa(limit))
	}
	if cursor != "" {
		query.Set("cursor", cursor)
	}
	var out SkillPage
	return out, c.do(ctx, http.MethodGet, "/skills", query, &out)
}

func (c *Client) Skill(ctx context.Context, id string) (Skill, error) {
	var out Skill
	return out, c.do(ctx, http.MethodGet, "/skills/"+url.PathEscape(id), nil, &out)
}

// Versions 取某个 Skill 的全部版本。
//
// 服务端按游标分页，一页只有 limit 条。CLI 的调用点（选版本、算云端摘要、
// 回滚到旧版）都假设拿到的是全部，所以这里把页翻完——CLI 是低频操作，
// 多几次往返比「找不到那个老版本」强。
func (c *Client) Versions(ctx context.Context, id string) (VersionPage, error) {
	query := url.Values{}
	query.Set("limit", "100")
	var all []SkillVersion
	cursor := ""
	for {
		if cursor == "" {
			query.Del("cursor")
		} else {
			query.Set("cursor", cursor)
		}
		var page VersionPage
		if err := c.do(ctx, http.MethodGet, "/skills/"+url.PathEscape(id)+"/versions", query, &page); err != nil {
			return VersionPage{}, err
		}
		all = append(all, page.Items...)
		if !page.HasMore || page.NextCursor == nil {
			return VersionPage{Items: all}, nil
		}
		cursor = *page.NextCursor
	}
}

// DownloadArtifact 取版本制品字节。走 stream 而不是 presigned URL，
// 避免把带权限的地址写进本地文件（详细设计 §12）。
func (c *Client) DownloadArtifact(ctx context.Context, artifactID string) ([]byte, error) {
	target := c.base + "/artifacts/" + url.PathEscape(artifactID) + "/download?disposition=stream"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, decodeProblem(resp)
	}
	var buf bytes.Buffer
	if _, err := io.Copy(&buf, resp.Body); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}
