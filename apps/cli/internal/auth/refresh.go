package auth

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"golang.org/x/oauth2"
)

// IssuerFromToken 从 access token 的 iss 声明里取 issuer。
//
// 登录时没把 issuer 存进登录态，但令牌自己知道是哪个 realm 签的——
// 少存一个配置项，老登录态也不用迁移。
func IssuerFromToken(accessToken string) (string, error) {
	parts := strings.Split(accessToken, ".")
	if len(parts) != 3 {
		return "", errors.New("access token 不是 JWT，取不到 issuer")
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return "", fmt.Errorf("解析 access token 失败: %w", err)
	}
	var claims struct {
		Issuer string `json:"iss"`
	}
	if err := json.Unmarshal(payload, &claims); err != nil {
		return "", fmt.Errorf("解析 access token 的声明失败: %w", err)
	}
	if claims.Issuer == "" {
		return "", errors.New("access token 里没有 iss 声明")
	}
	return claims.Issuer, nil
}

// Refresh 用 refresh token 换新的 access token。
//
// 令牌已过期时换——传进来的 Expiry 一律按过期算，
// 否则 oauth2 的 TokenSource 会觉得令牌还能用、原样返回。
func Refresh(ctx context.Context, clientID, issuer string, tokens Tokens) (Tokens, error) {
	if tokens.RefreshToken == "" {
		return Tokens{}, errors.New("没有 refresh token")
	}
	if issuer == "" {
		return Tokens{}, errors.New("缺少 issuer")
	}
	conf := &oauth2.Config{
		ClientID: clientID,
		Endpoint: oauth2.Endpoint{
			TokenURL: strings.TrimRight(issuer, "/") + "/protocol/openid-connect/token",
			// public client：client_id 放表单体，不用 Basic 头。
			// 默认的 AuthStyleAutoDetect 会先试 Basic，服务端一旦接受就再也不带 client_id 到表单里——
			// 碰上只认 params 的服务端，续期会莫名失败。
			AuthStyle: oauth2.AuthStyleInParams,
		},
	}
	source := conf.TokenSource(ctx, &oauth2.Token{
		AccessToken:  tokens.AccessToken,
		RefreshToken: tokens.RefreshToken,
		Expiry:       time.Now().Add(-time.Minute),
	})
	fresh, err := source.Token()
	if err != nil {
		return Tokens{}, err
	}
	tokens.AccessToken = fresh.AccessToken
	tokens.ExpiresAt = fresh.Expiry
	if fresh.RefreshToken != "" {
		tokens.RefreshToken = fresh.RefreshToken
	}
	return tokens, nil
}
