package cmd

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/packager"
	"github.com/handaome/skillmesh/apps/cli/internal/syncstate"
)

// 同步上传要带上「本次决策依据的云端摘要」：服务端才知道这次上传是基于哪个版本，
// 云端在这期间被别人动过就拒收（409），不会把那份改动静默盖掉。
// 普通 publish 没有「决策」这回事，不带基线。
func Test同步上传带基线普通发布不带(t *testing.T) {
	base := strings.Repeat("a", 64)
	captured := ""
	conflict := false
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/skills":
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"items":[{"id":"skill-1","slug":"demo","visibility":"private"}],` +
				`"has_more":false}`))
		case r.Method == http.MethodGet && r.URL.Path == "/skills/skill-1/versions":
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"items":[],"has_more":false}`))
		case r.Method == http.MethodPost && r.URL.Path == "/skills/skill-1/versions":
			raw, _ := io.ReadAll(r.Body)
			captured = string(raw)
			if conflict {
				w.Header().Set("Content-Type", "application/problem+json")
				w.WriteHeader(http.StatusConflict)
				w.Write([]byte(`{"status":409,"code":"VERSION_CONFLICT",` +
					`"detail":"云端已被别人改动，请重新对账"}`))
				return
			}
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"version_id":"v-1","status":"draft","upload":{` +
				`"session_id":"s-1","method":"PUT","url":"http://` + r.Host +
				`/put","headers":{}}}`))
		case r.Method == http.MethodPut && r.URL.Path == "/put":
			w.WriteHeader(http.StatusOK)
		case r.Method == http.MethodPost && r.URL.Path == "/uploads/s-1/complete":
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"id":"s-1","status":"completed","version_status":"published"}`))
		default:
			w.WriteHeader(http.StatusInternalServerError)
		}
	}))
	defer srv.Close()

	old := cfg.ConfigDir
	cfg.ConfigDir = t.TempDir()
	defer func() { cfg.ConfigDir = old }()

	dir := baselineSkillDir(t)
	artifact, err := packager.Pack(dir)
	if err != nil {
		t.Fatal(err)
	}
	client := apiclient.New(srv.URL, "tok", 0)
	target := syncTarget{Name: "demo", Agent: "claude-code", Path: dir,
		SkillID: "skill-1", CloudHash: base}

	result, _, err := applyDecision(context.Background(), client, target, syncstate.Upload,
		artifact, manifestDigest(artifact.Manifest), apiclient.Skill{ID: "skill-1"}, true,
		syncOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if result.Outcome != uploaded {
		t.Fatalf("该上传却没上传：%s（%s）", result.Outcome, result.Detail)
	}
	if !strings.Contains(captured, `"expected_base_revision":"`+base+`"`) {
		t.Fatalf("同步上传的建版本请求该带基线：%s", captured)
	}

	// 普通 publish 不带基线。
	if _, err := publishOne(context.Background(), client,
		publishTarget{Dir: dir, RelPath: "demo"},
		publishOptions{Visibility: "private"}); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(captured, "expected_base_revision") {
		t.Fatalf("普通发布不该带基线：%s", captured)
	}

	// 服务端说基线对不上：这是冲突，不是普通失败——要人来重新对账。
	conflict = true
	result, _, err = applyDecision(context.Background(), client, target, syncstate.Upload,
		artifact, manifestDigest(artifact.Manifest), apiclient.Skill{ID: "skill-1"}, true,
		syncOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if result.Outcome != conflicted {
		t.Fatalf("基线对不上该报冲突，得到 %s（%s）", result.Outcome, result.Detail)
	}
}

// baselineSkillDir 造一个最小的 Skill 目录。
func baselineSkillDir(t *testing.T) string {
	t.Helper()
	dir := filepath.Join(t.TempDir(), "demo")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	content := "---\nname: demo\ndescription: 演示用的 Skill\n---\n\n# demo\n"
	if err := os.WriteFile(filepath.Join(dir, "SKILL.md"), []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	return dir
}
