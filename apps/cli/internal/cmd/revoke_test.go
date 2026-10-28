package cmd

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/config"
)

// revokeServer 假服务端：认得按 slug 搜索与撤回两个接口。
type revokeServer struct {
	*httptest.Server
	gotMethod string
	gotPath   string
	gotBody   map[string]any
}

func newRevokeServer(t *testing.T, revokeStatus int) *revokeServer {
	t.Helper()
	fake := &revokeServer{}
	fake.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/skills":
			writeJSONTest(w, map[string]any{
				"items": []map[string]any{{"id": "s1", "slug": "demo"}},
			})
		case r.Method == http.MethodPost && r.URL.Path == "/skills/s1/versions/1.0.0/revoke":
			fake.gotMethod = r.Method
			fake.gotPath = r.URL.Path
			raw, _ := io.ReadAll(r.Body)
			_ = json.Unmarshal(raw, &fake.gotBody)
			if revokeStatus != http.StatusOK {
				w.WriteHeader(revokeStatus)
				writeJSONTest(w, map[string]any{
					"code": "VERSION_CONFLICT", "status": revokeStatus,
					"detail": "该版本已经是撤回状态"})
				return
			}
			writeJSONTest(w, map[string]any{
				"skill_id": "s1", "version": "1.0.0", "status": "revoked"})
		default:
			w.WriteHeader(http.StatusNotFound)
			writeJSONTest(w, map[string]any{"code": "NOT_FOUND", "message": "没有这个接口"})
		}
	}))
	t.Cleanup(fake.Close)
	return fake
}

func writeJSONTest(w http.ResponseWriter, body any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(body)
}

// runRevoke 跑一次 cloud revoke，把配置指到假服务端。
func runRevoke(t *testing.T, serverURL string, args ...string) error {
	t.Helper()
	Cfg = config.Config{Server: serverURL, CacheDir: t.TempDir()}
	t.Setenv("SKILLMESH_TOKEN", "test-token")
	cmd := newCloudRevokeCmd()
	cmd.SilenceUsage = true
	cmd.SilenceErrors = true
	cmd.SetArgs(args)
	return cmd.Execute()
}

// TestCloudRevokeCallsServer 撤回要打到正确的接口，并带上 reason。
//
// reason 的字段名必须跟服务端一致（RevokeVersionRequest.reason）：
// 写错字段不会报错，只是原因被悄悄丢掉——这种错只有靠断言请求体才发现。
func TestCloudRevokeCallsServer(t *testing.T) {
	server := newRevokeServer(t, http.StatusOK)
	if err := runRevoke(t, server.URL, "demo", "1.0.0", "--reason", "制品不是 zip"); err != nil {
		t.Fatalf("撤回失败: %v", err)
	}
	if server.gotMethod != http.MethodPost {
		t.Fatalf("方法 = %q, want POST", server.gotMethod)
	}
	if server.gotPath != "/skills/s1/versions/1.0.0/revoke" {
		t.Fatalf("路径 = %q, want /skills/s1/versions/1.0.0/revoke", server.gotPath)
	}
	if server.gotBody["reason"] != "制品不是 zip" {
		t.Fatalf("请求体 = %v, want reason=制品不是 zip", server.gotBody)
	}
}

// TestCloudRevokeServerConflict 服务端拒收时命令要报错，并把失败记进日志。
func TestCloudRevokeServerConflict(t *testing.T) {
	server := newRevokeServer(t, http.StatusConflict)
	err := runRevoke(t, server.URL, "demo", "1.0.0")
	if err == nil {
		t.Fatal("服务端 409 时命令应该报错")
	}
	if !strings.Contains(err.Error(), "撤回状态") {
		t.Fatalf("错误信息 = %q, want 提到撤回状态", err.Error())
	}
	logged := readOpLog(t)
	if !strings.Contains(logged, "version.revoke") {
		t.Fatalf("操作日志没记 version.revoke：%s", logged)
	}
	if !strings.Contains(logged, `"outcome":"failure"`) {
		t.Fatalf("操作日志没记失败：%s", logged)
	}
}

// TestCloudRevokeUnknownSlug 云端没有这个 Skill 时要说清楚，而不是报 404。
func TestCloudRevokeUnknownSlug(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		writeJSONTest(w, map[string]any{"items": []map[string]any{}})
	}))
	defer server.Close()
	err := runRevoke(t, server.URL, "demo", "1.0.0")
	if err == nil {
		t.Fatal("云端没有这个 Skill 时应该报错")
	}
	if !strings.Contains(err.Error(), "demo") {
		t.Fatalf("错误信息 = %q, want 提到 demo", err.Error())
	}
}

// readOpLog 读这次跑命令写下的操作日志。
func readOpLog(t *testing.T) string {
	t.Helper()
	raw, err := os.ReadFile(opLogPath())
	if err != nil {
		t.Fatalf("读操作日志失败: %v", err)
	}
	return string(raw)
}
