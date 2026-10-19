package verify

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"testing"
)

func Test验签通过与内容被换过(t *testing.T) {
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	key := PublicKey{
		Algorithm: "Ed25519", KeyID: "test-key", Enabled: true,
		PublicKey: base64.StdEncoding.EncodeToString(public),
	}
	digest := "a1b2c3"
	signature := base64.StdEncoding.EncodeToString(
		ed25519.Sign(private, []byte("test-key\n"+digest)))

	outcome, err := Check(key, signature, digest)
	if err != nil || outcome != Verified {
		t.Fatalf("期望验签通过，得到 %s / %v", outcome, err)
	}

	// 内容被换过 → 验不过
	if outcome, _ := Check(key, signature, "deadbeef"); outcome != Invalid {
		t.Fatalf("换了内容应验不过，得到 %s", outcome)
	}
	// 别的密钥签的，拿本机公钥去验 → 验不过
	_, otherPrivate, _ := ed25519.GenerateKey(rand.Reader)
	otherSignature := base64.StdEncoding.EncodeToString(
		ed25519.Sign(otherPrivate, []byte("test-key\n"+digest)))
	if outcome, _ := Check(key, otherSignature, digest); outcome != Invalid {
		t.Fatalf("别的密钥签的应验不过，得到 %s", outcome)
	}
}

func Test没签名就是没签名(t *testing.T) {
	key := PublicKey{KeyID: "k", Enabled: false}
	if outcome, err := Check(key, "", "digest"); outcome != NoSignature || err != nil {
		t.Fatalf("期望未签名，得到 %s / %v", outcome, err)
	}
}
