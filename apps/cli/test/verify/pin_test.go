package verify_test

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	verify "github.com/handaome/skillmesh/apps/cli/internal/verify"
	"testing"
)

// 钉住公钥之后，密钥换了就必须失败——这是信任根的意义所在。
func Test钉住的公钥不认别的密钥(t *testing.T) {
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	otherPublic, otherPrivate, _ := ed25519.GenerateKey(rand.Reader)
	_ = otherPublic

	digest := "a1b2c3"
	key := verify.PublicKey{
		Algorithm: "Ed25519", KeyID: "pinned-key", Enabled: true,
		PublicKey: base64.StdEncoding.EncodeToString(public),
	}
	signature := base64.StdEncoding.EncodeToString(
		ed25519.Sign(private, []byte("pinned-key\n"+digest)))

	if outcome, err := verify.Check(key, signature, digest); err != nil || outcome != verify.Verified {
		t.Fatalf("钉住的密钥自己签的应当通过，得到 %s / %v", outcome, err)
	}

	// 换一把密钥来签：验签过不了（这就是「换密钥必须先更新配置」的强制点）
	rotated := base64.StdEncoding.EncodeToString(
		ed25519.Sign(otherPrivate, []byte("pinned-key\n"+digest)))
	if outcome, _ := verify.Check(key, rotated, digest); outcome != verify.Invalid {
		t.Fatalf("别的密钥签的应当失败，得到 %s", outcome)
	}
}
