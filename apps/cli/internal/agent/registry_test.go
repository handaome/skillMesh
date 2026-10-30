package agent

import (
	"path/filepath"
	"strings"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/config"
)

func boolPtr(v bool) *bool { return &v }

// Hermes 按分类分层装，其它 Agent 还是一层。
func TestInstallPath按分类分层(t *testing.T) {
	Cfg := config.Config{Agents: map[string]config.Agent{
		"hermes": {
			Enabled:       true,
			UserSkillsDir: filepath.Join("/home", "u", ".hermes", "skills"),
			CategoryDirs:  boolPtr(true),
		},
		"claude-code": {
			Enabled:       true,
			UserSkillsDir: filepath.Join("/home", "u", ".claude", "skills"),
		},
	}}

	path, err := InstallPath(Cfg, "hermes", "user", "", "demo", "devops")
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join("/home", "u", ".hermes", "skills", "devops", "demo")
	if path != want {
		t.Fatalf("分层装到 %s，期望 %s", path, want)
	}

	path, err = InstallPath(Cfg, "claude-code", "user", "", "demo", "devops")
	if err != nil {
		t.Fatal(err)
	}
	want = filepath.Join("/home", "u", ".claude", "skills", "demo")
	if path != want {
		t.Fatalf("没开分层的装到 %s，期望 %s", path, want)
	}
}

// 分类是文件里的一句话，不是路径：拼进安装路径前不能让它跑到目录外面去。
func TestInstallPath分类不许跑出目录(t *testing.T) {
	base := filepath.Join("/home", "u", ".hermes", "skills")
	Cfg := config.Config{Agents: map[string]config.Agent{
		"hermes": {Enabled: true, UserSkillsDir: base, CategoryDirs: boolPtr(true)},
	}}
	path, err := InstallPath(Cfg, "hermes", "user", "", "demo", "../../etc")
	if err != nil {
		t.Fatal(err)
	}
	rel, err := filepath.Rel(base, path)
	if err != nil || strings.HasPrefix(rel, "..") {
		t.Fatalf("分类跑出了技能目录: %s", path)
	}
}
