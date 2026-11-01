package cmd

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/handaome/skillmesh/apps/cli/internal/auth"
)

// deviceCodeGrantType 是设备授权流程的 grant_type（RFC 8628）。
const deviceCodeGrantType = "urn:ietf:params:oauth:grant-type:device_code"

// deviceAuthorization 是设备授权端点的应答（RFC 8628）。
type deviceAuthorization struct {
	DeviceCode      string `json:"device_code"`
	UserCode        string `json:"user_code"`
	VerificationURI string `json:"verification_uri"`
	// VerificationURIComplete 是带好 code 的地址，用户扫一下就能授权。
	VerificationURIComplete string `json:"verification_uri_complete"`
	ExpiresIn               int    `json:"expires_in"`
	Interval                int    `json:"interval"`
}

// runDeviceFlow 走 OIDC 设备授权流程（RFC 8628）。
//
// 没有浏览器的机器上用这条：CLI 打印一个地址与一次性 code，
// 用户在能上网的设备上输入 code 授权，CLI 在这边轮询换令牌。
//
// 端点路径与 login.go 的授权码流程一致：这个 CLI 认的是 Keycloak 风格的
// OIDC 端点（/protocol/openid-connect/...）。
func runDeviceFlow(ctx context.Context, issuer, clientID string) (auth.Tokens, error) {
	issuer = strings.TrimRight(issuer, "/")
	device, err := requestDeviceCode(ctx, issuer, clientID)
	if err != nil {
		return auth.Tokens{}, err
	}
	fmt.Println("在浏览器中打开以下地址，输入一次性代码完成登录：")
	if device.VerificationURIComplete != "" {
		fmt.Println(device.VerificationURIComplete)
	} else {
		fmt.Println(device.VerificationURI)
	}
	fmt.Printf("一次性代码：%s\n", device.UserCode)

	interval := time.Duration(device.Interval) * time.Second
	if interval <= 0 {
		interval = 5 * time.Second
	}
	expires := time.Duration(device.ExpiresIn) * time.Second
	if expires <= 0 {
		expires = 10 * time.Minute
	}
	return pollDeviceToken(ctx, issuer, clientID, device.DeviceCode, interval, expires)
}

// requestDeviceCode 向设备授权端点要一个设备码。
func requestDeviceCode(ctx context.Context, issuer, clientID string) (deviceAuthorization, error) {
	var device deviceAuthorization
	form := url.Values{}
	form.Set("client_id", clientID)
	form.Set("scope", "openid profile email")
	endpoint := issuer + "/protocol/openid-connect/auth/device"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint,
		strings.NewReader(form.Encode()))
	if err != nil {
		return device, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return device, fmt.Errorf("请求设备授权失败: %w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return device, fmt.Errorf("请求设备授权失败（HTTP %d）：%s",
			resp.StatusCode, strings.TrimSpace(string(raw)))
	}
	if err := json.Unmarshal(raw, &device); err != nil {
		return device, fmt.Errorf("解析设备授权应答失败: %w", err)
	}
	if device.DeviceCode == "" {
		return device, fmt.Errorf("设备授权端点没给 device_code")
	}
	return device, nil
}

// pollDeviceToken 轮询令牌端点，直到用户授权完或超时。
//
// 服务端在用户还没输码时回 authorization_pending（HTTP 400）：
// 那是「还没好」，不是失败——所以 pending 与真正的错误要分开。
func pollDeviceToken(ctx context.Context, issuer, clientID, deviceCode string,
	interval, expires time.Duration) (auth.Tokens, error) {

	form := url.Values{}
	form.Set("grant_type", deviceCodeGrantType)
	form.Set("device_code", deviceCode)
	form.Set("client_id", clientID)
	endpoint := issuer + "/protocol/openid-connect/token"

	deadline := time.Now().Add(expires)
	for {
		timer := time.NewTimer(interval)
		select {
		case <-ctx.Done():
			timer.Stop()
			return auth.Tokens{}, ctx.Err()
		case <-timer.C:
		}
		token, code, err := requestDeviceToken(ctx, endpoint, form)
		switch code {
		case "":
			if err != nil {
				return auth.Tokens{}, err
			}
			return token, nil
		case "authorization_pending":
			// 用户还没输码，继续等。
		case "slow_down":
			// 服务端让我们慢一点：把间隔拉长，别把它逼急。
			interval += 5 * time.Second
		default:
			return auth.Tokens{}, fmt.Errorf("设备授权失败：%s", code)
		}
		if time.Now().After(deadline) {
			return auth.Tokens{}, fmt.Errorf("设备授权超时（%s）", expires)
		}
	}
}

// requestDeviceToken 换一次令牌。
//
// 第二个返回值是 OAuth 的错误码（authorization_pending / slow_down /
// expired_token / access_denied）；空串表示拿到了令牌。
func requestDeviceToken(ctx context.Context, endpoint string, form url.Values) (
	auth.Tokens, string, error) {

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint,
		strings.NewReader(form.Encode()))
	if err != nil {
		return auth.Tokens{}, "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return auth.Tokens{}, "", fmt.Errorf("请求令牌失败: %w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		var body struct {
			Error       string `json:"error"`
			Description string `json:"error_description"`
		}
		_ = json.Unmarshal(raw, &body)
		if body.Error == "" {
			return auth.Tokens{}, "", fmt.Errorf("请求令牌失败（HTTP %d）：%s",
				resp.StatusCode, strings.TrimSpace(string(raw)))
		}
		return auth.Tokens{}, body.Error, nil
	}
	var payload struct {
		AccessToken  string `json:"access_token"`
		RefreshToken string `json:"refresh_token"`
		ExpiresIn    int64  `json:"expires_in"`
	}
	if err := json.Unmarshal(raw, &payload); err != nil {
		return auth.Tokens{}, "", fmt.Errorf("解析令牌应答失败: %w", err)
	}
	return auth.Tokens{
		AccessToken:  payload.AccessToken,
		RefreshToken: payload.RefreshToken,
		ExpiresAt:    time.Now().Add(time.Duration(payload.ExpiresIn) * time.Second),
	}, "", nil
}
