package skillscan

import (
	"os"
	"path/filepath"
	"testing"
)

func TestSkillmeshignore排除规则(t *testing.T) {
	dir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dir, "references"), 0o755); err != nil {
		t.Fatal(err)
	}
	writeFile(t, filepath.Join(dir, IgnoreName), "# 注释\n\n*.log\ndist/\n/root-only.md\n")

	list, err := LoadIgnore(dir)
	if err != nil {
		t.Fatal(err)
	}
	cases := map[string]bool{
		"debug.log":            true,
		"references/trace.log": true,
		"dist/app.js":          true,
		"root-only.md":         true,
		"nested/root-only.md":  false, // 以 / 开头只锚定根目录
		"SKILL.md":             false,
		"references/notes.md":  false,
	}
	for path, want := range cases {
		if got := list.Match(path); got != want {
			t.Errorf("%s：期望 %v，得到 %v", path, want, got)
		}
	}
}

func Test凭据类文件不进摘要(t *testing.T) {
	dir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dir, "config"), 0o755); err != nil {
		t.Fatal(err)
	}
	writeFile(t, filepath.Join(dir, "SKILL.md"), "# demo\n")
	writeFile(t, filepath.Join(dir, ".env"), "SECRET=1\n")
	writeFile(t, filepath.Join(dir, "config", "server.key"), "-----BEGIN PRIVATE KEY-----\n")
	writeFile(t, filepath.Join(dir, "README.md"), "正常文件\n")

	files, err := DirHashes(dir)
	if err != nil {
		t.Fatal(err)
	}
	paths := make([]string, 0, len(files))
	for _, file := range files {
		paths = append(paths, file.Path)
	}
	if len(paths) != 2 {
		t.Fatalf("凭据文件不该进摘要，得到 %v", paths)
	}
}

func Test排除规则影响内容摘要(t *testing.T) {
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "SKILL.md"), "# demo\n")
	before, err := DirDigest(dir)
	if err != nil {
		t.Fatal(err)
	}
	writeFile(t, filepath.Join(dir, IgnoreName), "*.md\n")
	after, err := DirDigest(dir)
	if err != nil {
		t.Fatal(err)
	}
	if before == after {
		t.Fatal("加了排除规则后内容摘要必须跟着变")
	}
}
