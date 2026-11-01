package cmd

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/config"
	"github.com/handaome/skillmesh/apps/cli/internal/syncstate"
)

// 被监控的目录临时不可用（网盘掉了、权限变了、目录被删了）时，
// 不能把「看起来全没了」当成用户删了，往云端传一批删除
// （设计文档 §4.3 DEL-001、§3.6）。
func Test监控目录不可用时不往云端传批量删除(t *testing.T) {
	skillsDir := t.TempDir()
	first := filepath.Join(skillsDir, "one")
	second := filepath.Join(skillsDir, "two")
	for _, dir := range []string{first, second} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
		writeSkillFile(t, dir, demoSkillFile("内容"))
	}

	previous := Cfg
	defer func() { Cfg = previous }()
	Cfg = config.Config{
		ConfigDir: t.TempDir(),
		CacheDir:  t.TempDir(),
		Agents: map[string]config.Agent{
			"claude-code": {Enabled: true, UserSkillsDir: skillsDir},
		},
	}

	state := syncstate.State{Skills: []syncstate.Entry{
		{Name: "one", Agent: "claude-code", SkillID: "skill-1", Path: first},
		{Name: "two", Agent: "claude-code", SkillID: "skill-2", Path: second},
	}}

	// 目录临时不可用：里面的东西一下全没了（网盘掉了、目录被换掉了）。
	if err := os.RemoveAll(first); err != nil {
		t.Fatal(err)
	}
	if err := os.RemoveAll(second); err != nil {
		t.Fatal(err)
	}

	archived := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodDelete {
			archived++
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	results, _ := propagateLocalDeletes(context.Background(),
		apiclient.New(server.URL, "token", 0), &state, nil,
		SyncOptions{PropagateDelete: true})

	if archived != 0 {
		t.Fatalf("目录不可用时不该往云端传删除，却发了 %d 个删除请求", archived)
	}
	// 用户得看见为什么没删，不然只会以为同步没跑。
	blocked := false
	for _, result := range results {
		if result.Outcome == PendingDelete {
			blocked = true
		}
	}
	if !blocked {
		t.Fatalf("没往云端传删除，但也没说清为什么: %+v", results)
	}
}
