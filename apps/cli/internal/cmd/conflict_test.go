package cmd

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/config"
	"github.com/handaome/skillmesh/apps/cli/internal/packager"
	"github.com/handaome/skillmesh/apps/cli/internal/syncqueue"
	"github.com/handaome/skillmesh/apps/cli/internal/syncstate"
)

// 两台设备改了同一个文件：冲突得被发现，用户选了解决方式之后要接着往下走，
// 服务端那条「冲突中」的变更集也得被标记成已解决——不然另一台设备看到的
// 还是没解决（设计文档 §4.3 SYNC-002）。
func Test两设备并发改同一文件时按用户选择解决并同步冲突状态(t *testing.T) {
	dir := skillDirWith(t, "demo", demoSkillFile("本机的改动"))
	local, err := packager.Pack(dir)
	if err != nil {
		t.Fatal(err)
	}

	previous := Cfg
	defer func() { Cfg = previous }()
	Cfg = config.Config{ConfigDir: t.TempDir(), CacheDir: t.TempDir()}

	uploaded := false
	resolved := ""
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/skills":
			_ = json.NewEncoder(w).Encode(map[string]any{"items": []map[string]any{{
				"id": "skill-1", "slug": "demo", "name": "demo", "visibility": "private",
			}}})
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/skills/skill-1/versions":
			_ = json.NewEncoder(w).Encode(map[string]any{"items": []map[string]any{{
				"version": "1.0.0", "status": "published",
			}}})
		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/skills/skill-1/versions":
			uploaded = true
			_ = json.NewEncoder(w).Encode(map[string]any{
				"version_id": "v2",
				"upload": map[string]any{
					"session_id": "s1", "method": "PUT", "url": "/uploads/u1",
					"headers": map[string]string{},
				},
			})
		case r.Method == http.MethodPut && r.URL.Path == "/api/v1/uploads/u1":
			w.WriteHeader(http.StatusNoContent)
		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/uploads/s1/complete":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"id": "s1", "status": "completed", "version_status": "published",
			})
		case r.Method == http.MethodPost &&
			r.URL.Path == "/api/v1/sync/changesets/cs1/resolve":
			resolved = r.URL.Query().Get("resolution")
			_ = json.NewEncoder(w).Encode(map[string]any{
				"changeset_id": "cs1", "status": "completed",
			})
		default:
			t.Errorf("意外的请求：%s %s", r.Method, r.URL.Path)
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()

	target := SyncTarget{Name: "demo", Agent: "claude-code", Path: dir,
		ChangesetID: "cs1"}
	result, _, err := ApplyDecision(context.Background(),
		apiclient.New(server.URL, "token", 0), target, syncstate.Conflict,
		local, ManifestDigest(local.Manifest), apiclient.Skill{ID: "skill-1"}, true,
		SyncOptions{Resolve: "keep-local"})
	if err != nil {
		t.Fatalf("处理冲突失败: %v", err)
	}
	// 用户选了 keep-local：解决完就继续往下走，本机这份该传上去。
	if result.Outcome != Uploaded {
		t.Fatalf("选了 keep-local 就该继续上传，实际 %s（%s）", result.Outcome, result.Detail)
	}
	if !uploaded {
		t.Fatal("没有上传：冲突解决完该继续")
	}
	// 服务端那条变更集还挂着「冲突中」的话，另一台设备会一直看到冲突没解决。
	if resolved != "keep-local" {
		t.Fatalf("服务端那条冲突没被标记成已解决: %q", resolved)
	}

	// 冲突要人来拍板：退出码得是 7，脚本才知道该叫人而不是重试
	// （设计文档 §3.2 的退出码表）。
	// 退出码按「参数已校验」之后的口径算，所以先把那个状态置上。
	previousChecked := argsChecked
	argsChecked = true
	defer func() { argsChecked = previousChecked }()
	reportErr := reportSync([]syncResult{{Name: "demo", Outcome: Conflicted}},
		syncqueue.Queue{})
	if !errors.Is(reportErr, ErrConflict) || ExitCode(reportErr) != ExitConflict {
		t.Fatalf("冲突的退出码该是 %d，实际 %d（%v）",
			ExitConflict, ExitCode(reportErr), reportErr)
	}
}
