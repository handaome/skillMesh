package cmd

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/config"
	"github.com/handaome/skillmesh/apps/cli/internal/packager"
	"github.com/handaome/skillmesh/apps/cli/internal/syncstate"
)

// --dry-run 的承诺是「什么都不改」：撞上冲突时不能真上传、真下载、
// 真改本地文件（设计文档 §3.2、§3.6）。
func Test演练撞上冲突不上传也不下载(t *testing.T) {
	dir := skillDirWith(t, "demo", demoSkillFile("本机的改动"))
	local, err := packager.Pack(dir)
	if err != nil {
		t.Fatal(err)
	}

	previous := Cfg
	defer func() { Cfg = previous }()
	Cfg = config.Config{ConfigDir: t.TempDir(), CacheDir: t.TempDir()}

	calls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	target := SyncTarget{Name: "demo", Agent: "claude-code", Path: dir}
	result, _, err := ApplyDecision(context.Background(),
		apiclient.New(server.URL, "token", 0), target, syncstate.Conflict,
		local, ManifestDigest(local.Manifest), apiclient.Skill{ID: "skill-1"}, true,
		SyncOptions{Resolve: "keep-local", DryRun: true})
	if err != nil {
		t.Fatalf("演练不该失败: %v", err)
	}
	if calls != 0 {
		t.Fatalf("演练不该发任何请求，却发了 %d 个", calls)
	}
	if result.Outcome == Uploaded || result.Outcome == fetched {
		t.Fatalf("演练不该上传或下载: %s（%s）", result.Outcome, result.Detail)
	}
}
