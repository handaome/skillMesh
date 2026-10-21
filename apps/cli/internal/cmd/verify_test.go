package cmd

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/verify"
)

// 验签不通过就不能装。
//
// 原来 verifyArtifact 只返回一句话，install 把它 Printf 出来就接着 InstallAtomic——
// 「验签失败：装下来的内容和清单摘要对不上」打完之后，东西照样进了 Agent 目录，
// 等于没有闸门（spec §11 里「不符就拒装」说的就是这个）。
func Test验签不通过时报错(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(verify.PublicKey{
			Algorithm: "ed25519", KeyID: "k1", PublicKey: "不是公钥", Enabled: true,
		})
	}))
	defer srv.Close()

	verdict, err := verifyArtifact(context.Background(), apiclient.New(srv.URL, "tok", 0),
		apiclient.SkillVersion{
			Version: "1.0.0",
			Artifact: &apiclient.ArtifactSummary{
				ID: "a1", ContentDigest: "deadbeef", Signature: "c2ln",
			},
		}, t.TempDir())
	if err == nil {
		t.Fatalf("验签不通过该报错，实际 verdict=%q", verdict)
	}
	if !strings.Contains(err.Error(), "验签") {
		t.Fatalf("错误信息该说验签失败: %v", err)
	}
}

// 签名对得上、但本地算出的摘要与服务端声明的不一样——一样要拒装。
// 这条是「发布方声明一份干净清单、上传另一份字节」的最后一道兜底。
func Test本地摘要与服务端声明不符时报错(t *testing.T) {
	pub, priv, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}
	keyID := "k1"
	serverDigest := "服务端声明的摘要"
	signature := ed25519.Sign(priv, []byte(keyID+"\n"+serverDigest))

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(verify.PublicKey{
			Algorithm: "ed25519", KeyID: keyID,
			PublicKey: base64.StdEncoding.EncodeToString(pub), Enabled: true,
		})
	}))
	defer srv.Close()

	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "SKILL.md"),
		[]byte("---\nname: demo\ndescription: 摘要对不上\n---\n\n正文\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	verdict, err := verifyArtifact(context.Background(), apiclient.New(srv.URL, "tok", 0),
		apiclient.SkillVersion{
			Version: "1.0.0",
			Artifact: &apiclient.ArtifactSummary{
				ID: "a1", ContentDigest: serverDigest,
				Signature: base64.StdEncoding.EncodeToString(signature),
			},
		}, dir)
	if err == nil {
		t.Fatalf("摘要对不上该报错，实际 verdict=%q", verdict)
	}
	if !strings.Contains(err.Error(), "摘要") {
		t.Fatalf("错误信息该说是摘要对不上: %v", err)
	}
}
