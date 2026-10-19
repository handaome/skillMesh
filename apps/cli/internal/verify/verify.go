// Package verify 校验制品签名（设计文档 §10 制品完整性）。
//
// 验签证明的是「这份清单摘要确实出自注册中心当前的签名密钥」，
// 不等于注册中心本身可信——那需要另一套信任根（固定公钥或证书链）。
package verify

import (
	"crypto/ed25519"
	"encoding/base64"
	"fmt"
)

// PublicKey 是注册中心公布的验签公钥。
type PublicKey struct {
	Algorithm string `json:"algorithm"`
	KeyID     string `json:"key_id"`
	PublicKey string `json:"public_key"`
	Enabled   bool   `json:"enabled"`
}

// Outcome 是一次验签的结果。
type Outcome string

const (
	// Verified 签名对得上。
	Verified Outcome = "已验签"
	// NoSignature 服务端没签这一份（配置里没有私钥）。
	NoSignature Outcome = "未签名"
	// KeyMismatch 签名是别的密钥签的。
	KeyMismatch Outcome = "密钥不匹配"
	// Invalid 签名验不过——内容对不上，或被换过。
	Invalid Outcome = "验签失败"
)

func (o Outcome) String() string { return string(o) }

// Check 拿公钥验证「key_id + 内容摘要」的签名。
//
// signed 和 digest 都是服务端给的值：客户端验证的是服务端声称的摘要没被篡改，
// 装之前还会把本地算出的摘要和服务端的 content_digest 对一遍（见 Callers）。
func Check(key PublicKey, signatureB64, contentDigest string) (Outcome, error) {
	if !key.Enabled || key.PublicKey == "" {
		return NoSignature, nil
	}
	if signatureB64 == "" {
		return NoSignature, nil
	}
	raw, err := base64.StdEncoding.DecodeString(key.PublicKey)
	if err != nil || len(raw) != ed25519.PublicKeySize {
		return Invalid, fmt.Errorf("公钥格式不对")
	}
	signature, err := base64.StdEncoding.DecodeString(signatureB64)
	if err != nil || len(signature) != ed25519.SignatureSize {
		return Invalid, fmt.Errorf("签名格式不对")
	}
	message := []byte(key.KeyID + "\n" + contentDigest)
	if !ed25519.Verify(ed25519.PublicKey(raw), message, signature) {
		return Invalid, fmt.Errorf("签名与内容摘要对不上")
	}
	return Verified, nil
}
