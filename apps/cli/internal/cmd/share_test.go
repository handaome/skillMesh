package cmd

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/config"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
)

// 分类分层的 Agent 共享时，链接要放在分类下面——
// 与安装时同一套规则。放错了同一个技能就装了两份。
func TestShare分类分层的链接放在分类下面(t *testing.T) {
	root := t.TempDir()
	fromDir := filepath.Join(root, "claude", "skills")
	toDir := filepath.Join(root, "hermes", "skills")
	// 目标目录还没建：那儿就是没有技能，不该报错。
	if err := os.MkdirAll(fromDir, 0o755); err != nil {
		t.Fatal(err)
	}

	previous := Cfg
	defer func() { Cfg = previous }()
	Cfg = config.Config{Agents: map[string]config.Agent{
		"claude-code": {Enabled: true, UserSkillsDir: fromDir},
		"hermes":      {Enabled: true, UserSkillsDir: toDir, CategoryDirs: boolPtr(true)},
	}}

	// 源目录里的技能声明了分类。
	source := filepath.Join(fromDir, "demo")
	if err := os.MkdirAll(source, 0o755); err != nil {
		t.Fatal(err)
	}
	writeSkillFile(t, source,
		"---\nname: demo\ndescription: 演示\nmetadata:\n  hermes:\n    category: devops\n---\n\n正文。\n")

	plan, err := planShare(fromDir, toDir, "hermes")
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.link) != 1 {
		t.Fatalf("应该有一个要建链接的技能，实际 %d 个", len(plan.link))
	}
	want := filepath.Join(toDir, "devops", "demo")
	if plan.link[0].link != want {
		t.Fatalf("链接位置是 %s，期望 %s", plan.link[0].link, want)
	}

	if err := applyShare(fromDir, toDir, plan); err != nil {
		t.Fatal(err)
	}
	if _, ok := skillscan.LinkTarget(want); !ok {
		t.Fatalf("%s 不是链接", want)
	}
	// 平铺的位置不该再有一份：那是同一个技能装了两遍。
	if _, err := os.Lstat(filepath.Join(toDir, "demo")); err == nil {
		t.Fatalf("%s 下多了一份平铺的技能", toDir)
	}
}
