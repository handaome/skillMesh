package auth

import (
	"crypto/sha256"
	"encoding/base64"
	"strings"
	"testing"
)

func TestChallenge是verifier的SHA256Base64URL(t *testing.T) {
	pair := NewCodeVerifier()
	sum := sha256.Sum256([]byte(pair.Verifier))
	want := base64.RawURLEncoding.EncodeToString(sum[:])
	if pair.Challenge != want {
		t.Fatalf("challenge 不匹配\n得到 %s\n期望 %s", pair.Challenge, want)
	}
}

func TestChallenge只含URL安全字符(t *testing.T) {
	pair := NewCodeVerifier()
	if strings.ContainsAny(pair.Challenge, "+/=") {
		t.Fatalf("challenge 含非 URL-safe 字符: %s", pair.Challenge)
	}
}

func TestVerifier每次不同且足够长(t *testing.T) {
	a := NewCodeVerifier()
	b := NewCodeVerifier()
	if a.Verifier == b.Verifier {
		t.Fatal("两次生成相同 verifier")
	}
	if len(a.Verifier) < 43 {
		t.Fatalf("verifier 太短（RFC 7636 要求至少 43 字符）: %d", len(a.Verifier))
	}
}

func TestState每次不同(t *testing.T) {
	if RandomState() == RandomState() {
		t.Fatal("两次生成相同 state")
	}
}
