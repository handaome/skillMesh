// Package auth 处理 CLI 登录态：OIDC 授权码 + PKCE，凭据存系统凭据管理器。
package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
)

// Verifier 是 RFC 7636 的 S256 码对。
type Verifier struct {
	Verifier  string
	Challenge string
}

func NewCodeVerifier() Verifier {
	raw := make([]byte, 32)
	mustRead(rand.Read(raw))
	verifier := base64.RawURLEncoding.EncodeToString(raw)
	sum := sha256.Sum256([]byte(verifier))
	return Verifier{
		Verifier:  verifier,
		Challenge: base64.RawURLEncoding.EncodeToString(sum[:]),
	}
}

// RandomState 生成 CSRF 用的 state，回调时校验。
func RandomState() string {
	raw := make([]byte, 16)
	mustRead(rand.Read(raw))
	return base64.RawURLEncoding.EncodeToString(raw)
}

func mustRead(_ int, err error) {
	if err != nil {
		panic(err)
	}
}
