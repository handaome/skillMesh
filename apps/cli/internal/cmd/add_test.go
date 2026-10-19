package cmd

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/installer"
)

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

// makeRepoTree 造一个仓库目录树，返回根路径。
func makeRepoTree(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	writeFile(t, filepath.Join(root, "skills", "alpha", "SKILL.md"), "# A\n")
	writeFile(t, filepath.Join(root, "skills", "beta", "SKILL.md"), "# B\n")
	writeFile(t, filepath.Join(root, "docs", "readme.md"), "不是 Skill")
	return root
}

func Test指定path时直接用该目录(t *testing.T) {
	root := makeRepoTree(t)
	dir, err := locateSkillDir(root, "skills/alpha")
	if err != nil {
		t.Fatal(err)
	}
	if filepath.Base(dir) != "alpha" {
		t.Fatalf("选错了目录: %s", dir)
	}
}

func Test指定path不存在时报错(t *testing.T) {
	root := makeRepoTree(t)
	if _, err := locateSkillDir(root, "skills/nope"); err == nil {
		t.Fatal("期望报子目录不存在")
	}
}

func Test唯一候选时自动选中(t *testing.T) {
	root := t.TempDir()
	writeFile(t, filepath.Join(root, "only-one", "SKILL.md"), "# X\n")

	dir, err := locateSkillDir(root, "")
	if err != nil {
		t.Fatal(err)
	}
	if filepath.Base(dir) != "only-one" {
		t.Fatalf("选错了目录: %s", dir)
	}
}

func Test多个候选时要求显式指定(t *testing.T) {
	root := makeRepoTree(t)
	_, err := locateSkillDir(root, "")
	if err == nil || !strings.Contains(err.Error(), "--path") {
		t.Fatalf("期望提示用 --path 指定，得到 %v", err)
	}
	if !strings.Contains(err.Error(), "skills/alpha") || !strings.Contains(err.Error(), "skills/beta") {
		t.Fatalf("错误信息应列出候选目录: %v", err)
	}
}

func Test没有候选时报错(t *testing.T) {
	root := t.TempDir()
	writeFile(t, filepath.Join(root, "README.md"), "x")
	if _, err := locateSkillDir(root, ""); err == nil {
		t.Fatal("期望报未找到 Skill")
	}
}

func Test目标不存在时放行(t *testing.T) {
	if err := checkTarget(filepath.Join(t.TempDir(), "nope"), false); err != nil {
		t.Fatalf("目标不存在时应放行: %v", err)
	}
}

func Test同名且受管时放行(t *testing.T) {
	target := filepath.Join(t.TempDir(), "demo")
	if err := os.MkdirAll(target, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := installer.MarkInstalled(target, "https://example.com/x.git"); err != nil {
		t.Fatal(err)
	}
	if err := checkTarget(target, false); err != nil {
		t.Fatalf("受管目录应允许重装: %v", err)
	}
}

func Test同名且不受管时拒绝除非replace(t *testing.T) {
	target := filepath.Join(t.TempDir(), "demo")
	writeFile(t, filepath.Join(target, "SKILL.md"), "# 手写的\n")

	err := checkTarget(target, false)
	if err == nil || !strings.Contains(err.Error(), "--replace") {
		t.Fatalf("期望提示用 --replace，得到 %v", err)
	}
	if err := checkTarget(target, true); err != nil {
		t.Fatalf("--replace 时应放行: %v", err)
	}
}

func TestRefType判定(t *testing.T) {
	cases := map[string]string{
		"":       "default",
		"main":   "branch",
		"v1.2.0": "branch",
		"1234567890123456789012345678901234567890": "commit",
	}
	for ref, want := range cases {
		if got := refTypeOf(ref); got != want {
			t.Fatalf("ref %q 应判为 %s，实际 %s", ref, want, got)
		}
	}
}
