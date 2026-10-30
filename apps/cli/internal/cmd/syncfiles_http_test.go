package cmd

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/packager"
)

// 增量上传按变更集协议走完：开上传会话 → PUT 文件 → complete → commit。
//
// 请求与响应的形状照服务端写死（SyncUploadService.FileSpec / UploadSlot、
// SyncController.CommitRequest）：这里错一个字段名，服务端就会拒，
// 而这种错只有真发一次请求才看得出来。
func Test增量上传按变更集协议走完(t *testing.T) {
	root := filepath.Join(t.TempDir(), "probe")
	if err := os.MkdirAll(root, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "SKILL.md"), []byte("new"), 0o644); err != nil {
		t.Fatal(err)
	}

	var opened []map[string]any
	var uploaded []byte
	var committed map[string]any
	completed := false

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/sync/changesets/c1/uploads":
			raw, _ := io.ReadAll(r.Body)
			_ = json.Unmarshal(raw, &opened)
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode([]map[string]any{{
				"path": "SKILL.md", "upload_id": "u1",
				"upload_url": "/uploads/u1",
				"headers":    map[string]string{"content-type": "text/markdown"},
			}})
		case r.Method == http.MethodPut && r.URL.Path == "/api/v1/uploads/u1":
			uploaded, _ = io.ReadAll(r.Body)
			w.WriteHeader(http.StatusNoContent)
		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/uploads/u1/complete":
			completed = true
			_ = json.NewEncoder(w).Encode(map[string]any{"id": "u1", "status": "completed"})
		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/sync/changesets/c1/commit":
			raw, _ := io.ReadAll(r.Body)
			_ = json.Unmarshal(raw, &committed)
			_ = json.NewEncoder(w).Encode(map[string]any{
				"changeset_id": "c1", "status": "completed",
				"version": "1.0.1", "version_status": "published", "files": 2,
			})
		default:
			t.Errorf("意外的请求：%s %s", r.Method, r.URL.Path)
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()

	client := apiclient.New(server.URL, "token", 0)
	local := packager.Artifact{Manifest: []packager.Entry{
		{Path: "SKILL.md", SHA256: "abc", SizeBytes: 3},
	}}

	files, err := uploadChangedFiles(context.Background(), client, "c1", local,
		local.Manifest, root)
	if err != nil {
		t.Fatalf("上传失败: %v", err)
	}
	if files != 1 {
		t.Fatalf("该传 1 个文件，实际 %d", files)
	}
	// 开会话时声明的是这次要传的那几个文件
	if len(opened) != 1 || opened[0]["path"] != "SKILL.md" ||
		opened[0]["sha256"] != "abc" || opened[0]["size_bytes"] != float64(3) {
		t.Fatalf("开上传会话时声明的文件不对: %v", opened)
	}
	if string(uploaded) != "new" {
		t.Fatalf("传上去的内容不对: %q", uploaded)
	}
	if !completed {
		t.Fatal("传完没有 complete，服务端不会认这个文件")
	}

	result, err := client.CommitChangeset(context.Background(), "c1", "deadbeef")
	if err != nil {
		t.Fatalf("提交失败: %v", err)
	}
	// 提交要带上期望的云端基线：服务端拿它对账，对不上就回冲突
	if committed["expected_revision"] != "deadbeef" {
		t.Fatalf("提交时没带上期望基线: %v", committed)
	}
	if result["version_status"] != "published" || result["version"] != "1.0.1" {
		t.Fatalf("提交结果没读出来: %v", result)
	}
}
