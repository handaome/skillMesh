package cmd

import (
	"io/fs"
	"os"
	"path/filepath"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/config"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
)

func boolPtr(v bool) *bool { return &v }

// Hermes 官方布局是 <category>/<name>/SKILL.md。
//
// 本机没有 Hermes 本体（GitHub 不通，装不了），按官方文档写的发现规则验证：
// 递归扫技能目录找到 SKILL.md，再按 frontmatter 里的 name 认出来。
func TestHermes能发现装到分类目录下的Skill(t *testing.T) {
	skillsDir := t.TempDir()
	previous := Cfg
	defer func() { Cfg = previous }()
	Cfg = config.Config{Agents: map[string]config.Agent{
		"hermes": {Enabled: true, UserSkillsDir: skillsDir, CategoryDirs: boolPtr(true)},
	}}

	skillDir := skillDirWith(t, "demo",
		"---\nname: demo\ndescription: 演示用的 skill\nmetadata:\n  hermes:\n    category: devops\n---\n\n正文。\n")
	target, err := installTargetOf("hermes", "user", "", skillDir, "demo")
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(skillsDir, "devops", "demo")
	if target != want {
		t.Fatalf("装到 %s，期望 %s", target, want)
	}

	// 装过去，再用 Hermes 的发现规则找回来。
	if err := os.MkdirAll(target, 0o755); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(skillDir, skillscan.SkillFile))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(target, skillscan.SkillFile), data, 0o644); err != nil {
		t.Fatal(err)
	}
	if found := hermesDiscover(t, skillsDir, "demo"); found != target {
		t.Fatalf("Hermes 按 name 找到的是 %q，期望 %q", found, target)
	}
}

// hermesDiscover 按 Hermes 官方的发现规则找 Skill：
// 递归扫技能目录，认目录里的 SKILL.md，再按 frontmatter 的 name 匹配。
func hermesDiscover(t *testing.T, skillsDir, name string) string {
	t.Helper()
	found := ""
	err := filepath.WalkDir(skillsDir, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() || entry.Name() != skillscan.SkillFile {
			return nil
		}
		if skillscan.ReadMetadata(filepath.Dir(path)).Name == name {
			found = filepath.Dir(path)
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	return found
}
