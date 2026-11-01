package cmd

import (
	"context"
	"encoding/json"
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

// 用户确认过「目录是真的被清空了」时，删除就照传云端。
// 不然整目录删掉这件事永远传不上去：默认那道判断分不清
// 「用户删的」和「目录临时不可用」（设计文档 §4.3 DEL-001）。
func Test确认目录被清空后删除照传云端(t *testing.T) {
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
	// 整个目录被清空了。
	for _, dir := range []string{first, second} {
		if err := os.RemoveAll(dir); err != nil {
			t.Fatal(err)
		}
	}

	archived := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodDelete {
			archived++
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	propagateLocalDeletes(context.Background(),
		apiclient.New(server.URL, "token", 0), &state, nil,
		SyncOptions{PropagateDelete: true, ForceDelete: true})

	if archived != 2 {
		t.Fatalf("确认目录被清空后该把两份删除都传上去，却只发了 %d 个", archived)
	}
}

// 目录看不清时不能把云端那份装回来：装进去的用户根本看不见，
// 还把「本机没有」这个状态改了（设计文档 §3.6、§4.3 DEL-001）。
func Test目录看不清时不把云端那份装回来(t *testing.T) {
	skillsDir := filepath.Join(t.TempDir(), "skills") // 目录整个不在：网盘掉了
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
		{Name: "old", Agent: "claude-code", SkillID: "skill-1",
			Path: filepath.Join(skillsDir, "old")},
	}}

	downloaded := false
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/skills" {
			_ = json.NewEncoder(w).Encode(map[string]any{"items": []map[string]any{{
				"id": "skill-2", "slug": "new-one", "name": "new-one", "visibility": "private",
			}}})
			return
		}
		downloaded = true
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	results := pullMissing(context.Background(),
		apiclient.New(server.URL, "token", 0),
		SyncOptions{Agents: []string{"claude-code"}}, &state, nil)

	if downloaded {
		t.Fatal("目录看不清时不该把云端那份下载下来")
	}
	if _, err := os.Stat(filepath.Join(skillsDir, "new-one")); err == nil {
		t.Fatal("目录看不清时不该往本机装东西")
	}
	// 用户得看见为什么没装。
	if len(results) == 0 {
		t.Fatal("没装东西，但也没说清为什么")
	}
}
