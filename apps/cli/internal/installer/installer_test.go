package installer

import (
	"archive/zip"
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// makeZip 造一个内存里的 zip 制品。允许 "../" 这种非法条目，用来测拒绝逻辑。
func makeZip(t *testing.T, files map[string]string) []byte {
	t.Helper()
	var buf bytes.Buffer
	writer := zip.NewWriter(&buf)
	for name, content := range files {
		entry, err := writer.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := entry.Write([]byte(content)); err != nil {
			t.Fatal(err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func makeSkill(t *testing.T, files map[string]string) string {
	t.Helper()
	dir := filepath.Join(t.TempDir(), "src")
	for name, content := range files {
		path := filepath.Join(dir, filepath.FromSlash(name))
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return dir
}

func Test安装到目标目录(t *testing.T) {
	src := makeSkill(t, map[string]string{"SKILL.md": "# Demo\n", "refs/a.md": "a"})
	target := filepath.Join(t.TempDir(), "skills", "demo")

	if err := InstallAtomic(src, target, true); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(target, "SKILL.md"))
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "# Demo\n" {
		t.Fatalf("内容不对: %q", string(data))
	}
	if _, err := os.Stat(filepath.Join(target, "refs", "a.md")); err != nil {
		t.Fatalf("子目录没装上: %v", err)
	}
}

func Test缺SKILL_md时拒绝安装(t *testing.T) {
	src := makeSkill(t, map[string]string{"README.md": "x"})
	target := filepath.Join(t.TempDir(), "demo")

	err := InstallAtomic(src, target, true)
	if err == nil || !strings.Contains(err.Error(), "SKILL.md") {
		t.Fatalf("期望拒绝，得到 %v", err)
	}
	if _, err := os.Stat(target); !os.IsNotExist(err) {
		t.Fatal("拒绝安装时不应留下目标目录")
	}
}

func Test重装保留旧版本内容直到切换成功(t *testing.T) {
	target := filepath.Join(t.TempDir(), "demo")
	old := makeSkill(t, map[string]string{"SKILL.md": "# old\n"})
	if err := InstallAtomic(old, target, true); err != nil {
		t.Fatal(err)
	}

	fresh := makeSkill(t, map[string]string{"SKILL.md": "# new\n"})
	if err := InstallAtomic(fresh, target, true); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(target, "SKILL.md"))
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "# new\n" {
		t.Fatalf("未切换到新版本: %q", string(data))
	}
	// 备份目录不应残留
	if _, err := os.Stat(target + backupSuffix); !os.IsNotExist(err) {
		t.Fatal("备份目录未清理")
	}
}

func Test不留暂存目录(t *testing.T) {
	base := t.TempDir()
	src := makeSkill(t, map[string]string{"SKILL.md": "# x\n"})
	if err := InstallAtomic(src, filepath.Join(base, "demo"), true); err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(base)
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), stagingPrefix) {
			t.Fatalf("暂存目录未清理: %s", entry.Name())
		}
	}
}

func Test受管标记(t *testing.T) {
	src := makeSkill(t, map[string]string{"SKILL.md": "# x\n"})
	target := filepath.Join(t.TempDir(), "demo")

	if IsManaged(target) {
		t.Fatal("未标记的目录不应算受管")
	}
	if err := InstallAtomic(src, target, true); err != nil {
		t.Fatal(err)
	}
	if err := MarkInstalled(target, "https://example.com/x.git"); err != nil {
		t.Fatal(err)
	}
	if !IsManaged(target) {
		t.Fatal("标记后应算受管")
	}
}

func Test拒绝删除不受管目录(t *testing.T) {
	target := filepath.Join(t.TempDir(), "handwritten")
	if err := os.MkdirAll(target, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(target, "SKILL.md"), []byte("# 手写的\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	err := RemoveUnmanaged(target)
	if err == nil || !strings.Contains(err.Error(), "不由 SkillMesh 管理") {
		t.Fatalf("期望拒绝删除，得到 %v", err)
	}
	if _, err := os.Stat(target); err != nil {
		t.Fatal("目录被误删")
	}
}

func Test删除不存在的目录不报错(t *testing.T) {
	if err := RemoveUnmanaged(filepath.Join(t.TempDir(), "nope")); err != nil {
		t.Fatalf("期望 nil，得到 %v", err)
	}
}

func Test解包拒绝路径穿越条目(t *testing.T) {
	evil := makeZip(t, map[string]string{
		"../escaped.txt": "pwned",
		"/absolute.txt":  "pwned",
	})
	dir := t.TempDir()
	if _, err := ExtractZip(evil, dir); err == nil ||
		!strings.Contains(err.Error(), "路径穿越") {
		t.Fatalf("期望拒绝路径穿越，得到 %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, "..", "escaped.txt")); err == nil {
		t.Fatal("文件被写到目录外")
	}
}

func Test解包合法制品并识别Skill根(t *testing.T) {
	good := makeZip(t, map[string]string{
		"my-skill/SKILL.md":        "# Demo\n",
		"my-skill/references/a.md": "a",
	})
	root := filepath.Join(t.TempDir(), "unpacked")
	skillDir, err := ExtractZip(good, root)
	if err != nil {
		t.Fatal(err)
	}
	if filepath.Base(skillDir) != "my-skill" {
		t.Fatalf("Skill 根识别不对: %s", skillDir)
	}
	if _, err := os.Stat(filepath.Join(skillDir, "SKILL.md")); err != nil {
		t.Fatal(err)
	}
}

func Test解包顶层直接是SKILL_md(t *testing.T) {
	flat := makeZip(t, map[string]string{"SKILL.md": "# Flat\n"})
	skillDir, err := ExtractZip(flat, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(skillDir, "SKILL.md")); err != nil {
		t.Fatal(err)
	}
}
