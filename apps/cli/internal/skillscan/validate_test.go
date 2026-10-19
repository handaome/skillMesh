package skillscan

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func testLimits() Limits {
	return Limits{MaxFileBytes: 1 << 20, MaxTotalBytes: 10 << 20, MaxFiles: 100}
}

func write(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func Test允许正常的Skill目录(t *testing.T) {
	dir := t.TempDir()
	write(t, filepath.Join(dir, "SKILL.md"), "# Demo\n")
	write(t, filepath.Join(dir, "references", "guide.md"), "x")

	if err := ValidateDir(dir, testLimits()); err != nil {
		t.Fatalf("合法目录被拒绝: %v", err)
	}
}

func Test拒绝没有SKILL_md的目录(t *testing.T) {
	dir := t.TempDir()
	write(t, filepath.Join(dir, "README.md"), "x")

	err := ValidateDir(dir, testLimits())
	if err == nil || !strings.Contains(err.Error(), "SKILL.md") {
		t.Fatalf("期望 SKILL.md 缺失错误，得到 %v", err)
	}
}

func Test拒绝逃出根目录的符号链接(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	write(t, filepath.Join(outside, "secret.txt"), "s3cret")

	dir := filepath.Join(root, "skill")
	write(t, filepath.Join(dir, "SKILL.md"), "# D\n")
	if err := os.Symlink(outside, filepath.Join(dir, "link")); err != nil {
		t.Skipf("当前环境不支持创建符号链接: %v", err)
	}

	err := ValidateDir(dir, testLimits())
	if err == nil || !strings.Contains(err.Error(), "符号链接") {
		t.Fatalf("期望符号链接被拒，得到 %v", err)
	}
}

func Test允许指向目录内的符号链接(t *testing.T) {
	dir := t.TempDir()
	write(t, filepath.Join(dir, "SKILL.md"), "# D\n")
	write(t, filepath.Join(dir, "references", "a.md"), "x")
	if err := os.Symlink(filepath.Join(dir, "references"), filepath.Join(dir, "ref")); err != nil {
		t.Skipf("当前环境不支持创建符号链接: %v", err)
	}

	if err := ValidateDir(dir, testLimits()); err != nil {
		t.Fatalf("目录内符号链接应被允许: %v", err)
	}
}

func Test拒绝超限文件(t *testing.T) {
	dir := t.TempDir()
	write(t, filepath.Join(dir, "SKILL.md"), "# D\n")
	write(t, filepath.Join(dir, "big.bin"), strings.Repeat("x", 2048))

	err := ValidateDir(dir, Limits{MaxFileBytes: 512, MaxTotalBytes: 10 << 20, MaxFiles: 100})
	if err == nil || !strings.Contains(err.Error(), "大小") {
		t.Fatalf("期望大小超限错误，得到 %v", err)
	}
}

func Test拒绝文件数超限(t *testing.T) {
	dir := t.TempDir()
	write(t, filepath.Join(dir, "SKILL.md"), "# D\n")
	for i := 0; i < 10; i++ {
		write(t, filepath.Join(dir, "f"+string(rune('a'+i))), "x")
	}

	err := ValidateDir(dir, Limits{MaxFileBytes: 1 << 20, MaxTotalBytes: 10 << 20, MaxFiles: 5})
	if err == nil || !strings.Contains(err.Error(), "文件数") {
		t.Fatalf("期望文件数超限错误，得到 %v", err)
	}
}

func Test忽略git等噪声目录(t *testing.T) {
	dir := t.TempDir()
	write(t, filepath.Join(dir, "SKILL.md"), "# D\n")
	write(t, filepath.Join(dir, ".git", "config"), "[core]")
	write(t, filepath.Join(dir, "node_modules", "x", "index.js"), "junk")

	if err := ValidateDir(dir, testLimits()); err != nil {
		t.Fatalf("含噪声目录的合法 Skill 被拒: %v", err)
	}
}

func TestCandidates找出含SKILL_md的子目录(t *testing.T) {
	root := t.TempDir()
	write(t, filepath.Join(root, "skills", "alpha", "SKILL.md"), "# A\n")
	write(t, filepath.Join(root, "skills", "beta", "SKILL.md"), "# B\n")
	write(t, filepath.Join(root, "skills", "gamma", "README.md"), "不是 Skill")

	found, err := Candidates(root)
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, c := range found {
		names = append(names, c.Name)
	}
	if len(names) != 2 {
		t.Fatalf("期望 2 个候选，得到 %v", names)
	}
}

func TestSkillName优先读frontmatter(t *testing.T) {
	dir := t.TempDir()
	write(t, filepath.Join(dir, "SKILL.md"), "---\nname: my-skill\ndescription: d\n---\n")

	if got := SkillName(dir); got != "my-skill" {
		t.Fatalf("期望读 frontmatter 的 name，得到 %s", got)
	}
}

func TestSkillName回退到目录名(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "fallback-name")
	write(t, filepath.Join(dir, "SKILL.md"), "# 没有 frontmatter\n")

	if got := SkillName(dir); got != "fallback-name" {
		t.Fatalf("期望回退到目录名，得到 %s", got)
	}
}
