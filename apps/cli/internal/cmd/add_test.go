package cmd

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/installer"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
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
	candidates, err := locateCandidates(root, "skills/alpha", false)
	if err != nil {
		t.Fatal(err)
	}
	if len(candidates) != 1 || filepath.Base(candidates[0].Dir) != "alpha" {
		t.Fatalf("选错了目录: %+v", candidates)
	}
}

func Test指定path不存在时报错(t *testing.T) {
	root := makeRepoTree(t)
	if _, err := locateCandidates(root, "skills/nope", false); err == nil {
		t.Fatal("期望报子目录不存在")
	}
}

func Test唯一候选时自动选中(t *testing.T) {
	root := t.TempDir()
	writeFile(t, filepath.Join(root, "only-one", "SKILL.md"), "# X\n")

	candidates, err := locateCandidates(root, "", false)
	if err != nil {
		t.Fatal(err)
	}
	if len(candidates) != 1 || filepath.Base(candidates[0].Dir) != "only-one" {
		t.Fatalf("选错了目录: %+v", candidates)
	}
}

func Test多个候选时要求显式指定或用all(t *testing.T) {
	root := makeRepoTree(t)
	_, err := locateCandidates(root, "", false)
	if err == nil || !strings.Contains(err.Error(), "--path") {
		t.Fatalf("期望提示用 --path 指定，得到 %v", err)
	}
	if !strings.Contains(err.Error(), "--all") {
		t.Fatalf("错误信息应提示 --all: %v", err)
	}
	if !strings.Contains(err.Error(), "skills/alpha") || !strings.Contains(err.Error(), "skills/beta") {
		t.Fatalf("错误信息应列出候选目录: %v", err)
	}
}

func TestAll时返回全部候选(t *testing.T) {
	root := makeRepoTree(t)
	candidates, err := locateCandidates(root, "", true)
	if err != nil {
		t.Fatal(err)
	}
	if len(candidates) != 2 {
		t.Fatalf("期望 2 个候选，得到 %d", len(candidates))
	}
}

func Test没有候选时报错(t *testing.T) {
	root := t.TempDir()
	writeFile(t, filepath.Join(root, "README.md"), "x")
	if _, err := locateCandidates(root, "", true); err == nil {
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

// --- planInstalls ---

func Test计划全部通过时都装(t *testing.T) {
	root := makeRepoTree(t)
	candidates, err := skillscan.Candidates(root)
	if err != nil {
		t.Fatal(err)
	}
	plan := planInstalls(root, candidates, t.TempDir(), false)

	if len(plan.Install) != 2 {
		t.Fatalf("期望装 2 个，得到 %d", len(plan.Install))
	}
	if len(plan.Skip) != 0 || len(plan.Fail) != 0 {
		t.Fatalf("不该有跳过或失败: %+v", plan)
	}
}

func Test单个Skill校验失败不影响其他(t *testing.T) {
	root := t.TempDir()
	writeFile(t, filepath.Join(root, "good", "SKILL.md"), "# G\n")
	// 坏目录：有 SKILL.md 但符号链接指向目录外
	bad := filepath.Join(root, "bad")
	writeFile(t, filepath.Join(bad, "SKILL.md"), "# B\n")
	outside := t.TempDir()
	if err := os.Symlink(outside, filepath.Join(bad, "escape")); err != nil {
		t.Skipf("当前环境不支持符号链接: %v", err)
	}

	candidates, err := skillscan.Candidates(root)
	if err != nil {
		t.Fatal(err)
	}
	plan := planInstalls(root, candidates, t.TempDir(), false)

	if len(plan.Install) != 1 || plan.Install[0].Name != "good" {
		t.Fatalf("应只装 good: %+v", plan.Install)
	}
	if len(plan.Fail) != 1 {
		t.Fatalf("应有 1 个失败: %+v", plan.Fail)
	}
	if !strings.Contains(plan.Fail[0].Why, "符号链接") {
		t.Fatalf("失败原因应提到符号链接: %s", plan.Fail[0].Why)
	}
}

func Test安装名冲突时跳过后者(t *testing.T) {
	root := t.TempDir()
	// 两个目录的 frontmatter 里 name 相同
	writeFile(t, filepath.Join(root, "a", "SKILL.md"), "---\nname: same\n---\n")
	writeFile(t, filepath.Join(root, "b", "SKILL.md"), "---\nname: same\n---\n")

	candidates, err := skillscan.Candidates(root)
	if err != nil {
		t.Fatal(err)
	}
	plan := planInstalls(root, candidates, t.TempDir(), false)

	if len(plan.Install) != 1 {
		t.Fatalf("应只装 1 个: %+v", plan.Install)
	}
	if len(plan.Skip) != 1 || !strings.Contains(plan.Skip[0].Why, "冲突") {
		t.Fatalf("应报告冲突: %+v", plan.Skip)
	}
}

func Test目标被占时跳过不覆盖(t *testing.T) {
	root := t.TempDir()
	writeFile(t, filepath.Join(root, "alpha", "SKILL.md"), "# A\n")
	base := t.TempDir()
	writeFile(t, filepath.Join(base, "alpha", "SKILL.md"), "# 手写的\n")

	candidates, err := skillscan.Candidates(root)
	if err != nil {
		t.Fatal(err)
	}
	plan := planInstalls(root, candidates, base, false)

	if len(plan.Install) != 0 {
		t.Fatalf("不该装: %+v", plan.Install)
	}
	if len(plan.Skip) != 1 || !strings.Contains(plan.Skip[0].Why, "--replace") {
		t.Fatalf("应报告需要 --replace: %+v", plan.Skip)
	}
	// 原有内容不能被动过
	data, err := os.ReadFile(filepath.Join(base, "alpha", "SKILL.md"))
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "# 手写的\n" {
		t.Fatalf("原有内容被改了: %q", string(data))
	}
}
