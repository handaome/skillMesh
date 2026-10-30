package cmd

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
)

// skillDirWith 造一个只含 SKILL.md 的 Skill 目录。
func skillDirWith(t *testing.T, name, content string) string {
	t.Helper()
	dir := filepath.Join(t.TempDir(), name)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	writeSkillFile(t, dir, content)
	return dir
}

func writeSkillFile(t *testing.T, dir, content string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, skillscan.SkillFile), []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestFixSkillFile补齐缺的字段(t *testing.T) {
	dir := skillDirWith(t, "demo", "# 标题\n\n这是一个演示用的 skill。\n")
	fixed, changed, err := fixSkillFile(dir)
	if err != nil {
		t.Fatal(err)
	}
	if !changed {
		t.Fatal("应该有改动")
	}
	if err := skillscan.ValidateSpecContent(fixed); err != nil {
		t.Fatalf("补完还是不合规: %v\n%s", err, fixed)
	}
	if !strings.Contains(fixed, "name: demo") {
		t.Fatalf("没补上 name:\n%s", fixed)
	}
	if !strings.Contains(fixed, "description: 这是一个演示用的 skill。") {
		t.Fatalf("没补上 description:\n%s", fixed)
	}
}

func TestFixSkillFile不覆盖已有字段(t *testing.T) {
	dir := skillDirWith(t, "demo",
		"---\nname: my-name\ndescription: 我自己的描述\n---\n\n正文。\n")
	_, changed, err := fixSkillFile(dir)
	if err != nil {
		t.Fatal(err)
	}
	if changed {
		t.Fatal("已有的字段不该被改")
	}
}

func TestFixSkillFile补不出描述时报错(t *testing.T) {
	dir := skillDirWith(t, "demo", "# 只有标题\n")
	if _, _, err := fixSkillFile(dir); err == nil {
		t.Fatal("补不出描述就该报错")
	}
}

// 声明了豁免的 skill 本来就合规，不该被工具动过。
func TestFixSkillFile豁免的skill不动(t *testing.T) {
	dir := skillDirWith(t, "demo",
		"---\nmetadata:\n  skillmesh:\n    spec_exempt: true\n---\n\n正文。\n")
	_, changed, err := fixSkillFile(dir)
	if err != nil {
		t.Fatal(err)
	}
	if changed {
		t.Fatal("豁免的 skill 不该被改")
	}
}
