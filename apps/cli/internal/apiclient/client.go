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

	target := c.base + path
	if len(query) > 0 {
		target += "?" + query.Encode()
	}
	var reader io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			return err
		}
		reader = bytes.NewReader(raw)
	}
	req, err := http.NewRequestWithContext(ctx, method, target, reader)
	if err != nil {
		return err
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
		return fmt.Errorf("请求 %s 失败: %w", target, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return decodeProblem(resp)
	}
	if out == nil {
		_, _ = io.Copy(io.Discard, resp.Body)
		return nil
	}
	return json.NewDecoder(resp.Body).Decode(out)
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

func (c *Client) Versions(ctx context.Context, id string) (VersionPage, error) {
	var out VersionPage
	return out, c.do(ctx, http.MethodGet, "/skills/"+url.PathEscape(id)+"/versions", nil, &out)
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
