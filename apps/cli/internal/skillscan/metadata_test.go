package skillscan

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func writeSkillMd(t *testing.T, dir, content string) {
	t.Helper()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "SKILL.md"), []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func Test读frontmatter(t *testing.T) {
	dir := t.TempDir()
	writeSkillMd(t, dir, "---\nname: java-debug\ndescription: 调试 Java 服务\nversion: 1.2.0\n---\n\n# 正文\n")

	meta := ReadMetadata(dir)
	if meta.Name != "java-debug" {
		t.Fatalf("name 不对: %q", meta.Name)
	}
	if meta.Description != "调试 Java 服务" {
		t.Fatalf("description 不对: %q", meta.Description)
	}
	if meta.Version != "1.2.0" {
		t.Fatalf("version 不对: %q", meta.Version)
	}
}

func Test没有frontmatter时返回空(t *testing.T) {
	dir := t.TempDir()
	writeSkillMd(t, dir, "# 就一个标题\n")

	if meta := ReadMetadata(dir); meta.Name != "" || meta.Description != "" {
		t.Fatalf("应返回空元信息，得到 %+v", meta)
	}
}

func TestFrontmatter损坏时返回空而不是报错(t *testing.T) {
	dir := t.TempDir()
	writeSkillMd(t, dir, "---\nname: [未闭合\n---\n")

	if meta := ReadMetadata(dir); meta.Name != "" {
		t.Fatalf("应返回空元信息，得到 %+v", meta)
	}
}

func TestCRLF也能读(t *testing.T) {
	dir := t.TempDir()
	writeSkillMd(t, dir, "---\r\nname: crlf-skill\r\ndescription: d\r\n---\r\n")

	if meta := ReadMetadata(dir); meta.Name != "crlf-skill" {
		t.Fatalf("CRLF 文件应能读出 name，得到 %q", meta.Name)
	}
}

func TestSlug从目录名(t *testing.T) {
	cases := map[string]string{
		"java-debug": "java-debug",
		"Java-Debug": "java-debug",
		"java_debug": "java-debug",
		"my skill":   "my-skill",
	}
	for input, want := range cases {
		got, err := SlugFromDirName(input)
		if err != nil {
			t.Fatalf("%q 不该报错: %v", input, err)
		}
		if got != want {
			t.Fatalf("%q 应得 %s，实际 %s", input, want, got)
		}
	}
}

func TestSlug拒绝非法字符(t *testing.T) {
	for _, bad := range []string{"java/debug", "中文名", "java.debug", "a"} {
		if _, err := SlugFromDirName(bad); err == nil {
			t.Fatalf("期望拒绝 %q", bad)
		}
	}
}

func TestSlug错误信息提示用斜杠参数(t *testing.T) {
	_, err := SlugFromDirName("a")
	if err == nil || !strings.Contains(err.Error(), "--slug") {
		t.Fatalf("错误信息应提示 --slug，得到 %v", err)
	}
}
