package apiclient

import (
	"context"
	"fmt"
	"net/http"
	"time"
)

// LocalIssuer 是内置账号令牌的签发方。
//
// 服务端自签的令牌都用这个 issuer，客户端靠它把内置账号的续期
// 和外部 IdP 的续期分开——两者用的是完全不同的接口。
const LocalIssuer = "skillmesh-local"

// PasswordTokens 是内置账号登录/注册后拿到的令牌。
type PasswordTokens struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ExpiresIn    int64  `json:"expires_in"`
	Username     string `json:"username"`
}

// PasswordLogin 用用户名 + 密码登录，换回令牌。
//
// 这条路不需要浏览器：CLI 把口令直接发给服务端。
func PasswordLogin(ctx context.Context, server, username, password string) (PasswordTokens, error) {
	client := New(server, "", 30*time.Second)
	var tokens PasswordTokens
	body := map[string]string{"username": username, "password": password}
	if err := client.doWith(ctx, http.MethodPost, "/auth/login", nil, body, &tokens); err != nil {
		return PasswordTokens{}, fmt.Errorf("登录失败: %w", err)
	}
	return tokens, nil
}

// RegisterAccount 注册一个内置账号，服务端允许自助注册时才能用。
//
// 注册成功就直接登录了：服务端返回的就是一对令牌。
func RegisterAccount(ctx context.Context, server, username, password,
	displayName, email string) (PasswordTokens, error) {

	client := New(server, "", 30*time.Second)
	body := map[string]string{"username": username, "password": password}
	if displayName != "" {
		body["display_name"] = displayName
	}
	if email != "" {
		body["email"] = email
	}
	var tokens PasswordTokens
	if err := client.doWith(ctx, http.MethodPost, "/auth/register", nil, body, &tokens); err != nil {
		return PasswordTokens{}, fmt.Errorf("注册失败: %w", err)
	}
	return tokens, nil
}

// RefreshLocal 用刷新令牌换一对新令牌（内置账号这条路）。
//
// 外部 IdP 的续期走 IdP 自己的 token 端点，内置账号没有 IdP：
// 只能问服务端要。
func RefreshLocal(ctx context.Context, server, refreshToken string) (PasswordTokens, error) {
	client := New(server, "", 30*time.Second)
	var tokens PasswordTokens
	body := map[string]string{"refresh_token": refreshToken}
	if err := client.doWith(ctx, http.MethodPost, "/auth/refresh", nil, body, &tokens); err != nil {
		return PasswordTokens{}, fmt.Errorf("续期失败: %w", err)
	}
	return tokens, nil
}
