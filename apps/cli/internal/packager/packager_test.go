package packager

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func write(t *testing.T, path, content string, mode os.FileMode) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), mode); err != nil {
		t.Fatal(err)
	}
}

func Test打包后根目录是SKILL_md(t *testing.T) {
	dir := t.TempDir()
	write(t, filepath.Join(dir, "SKILL.md"), "# Demo\n", 0o644)
	write(t, filepath.Join(dir, "references", "a.md"), "a\n", 0o644)

	artifact, err := Pack(dir)
	if err != nil {
		t.Fatal(err)
	}
	reader, err := zip.NewReader(bytes.NewReader(artifact.Zip), int64(len(artifact.Zip)))
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, f := range reader.File {
		names = append(names, f.Name)
	}
	if len(names) != 2 || names[0] != "SKILL.md" || names[1] != "references/a.md" {
		t.Fatalf("包内结构不对: %v", names)
	}
}

func Test摘要是整包的摘要(t *testing.T) {
	dir := t.TempDir()
	write(t, filepath.Join(dir, "SKILL.md"), "# Demo\n", 0o644)

	artifact, err := Pack(dir)
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(artifact.Zip)
	if artifact.SHA256 != hex.EncodeToString(sum[:]) {
		t.Fatalf("整包摘要不对: %s", artifact.SHA256)
	}
	if artifact.SizeBytes != int64(len(artifact.Zip)) {
		t.Fatalf("大小不对: %d", artifact.SizeBytes)
	}
}

func TestManifest逐文件带摘要(t *testing.T) {
	dir := t.TempDir()
	content := "# Demo\n"
	write(t, filepath.Join(dir, "SKILL.md"), content, 0o644)

	artifact, err := Pack(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(artifact.Manifest) != 1 {
		t.Fatalf("期望 1 条 manifest，得到 %d", len(artifact.Manifest))
	}
	entry := artifact.Manifest[0]
	if entry.Path != "SKILL.md" {
		t.Fatalf("路径不对: %s", entry.Path)
	}
	sum := sha256.Sum256([]byte(content))
	if entry.SHA256 != hex.EncodeToString(sum[:]) {
		t.Fatalf("文件摘要不对: %s", entry.SHA256)
	}
	if entry.SizeBytes != int64(len(content)) {
		t.Fatalf("文件大小不对: %d", entry.SizeBytes)
	}
	if entry.Executable {
		t.Fatal("普通文件不该标记为可执行")
	}
}

func Test可执行文件被标记(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows 没有 Unix 可执行位，Go 一律报 0666，标记不了")
	}
	dir := t.TempDir()
	write(t, filepath.Join(dir, "SKILL.md"), "# Demo\n", 0o644)
	write(t, filepath.Join(dir, "scripts", "run.sh"), "#!/bin/sh\n", 0o755)

	artifact, err := Pack(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range artifact.Manifest {
		if entry.Path == "scripts/run.sh" && !entry.Executable {
			t.Fatal("shell 脚本应标记为可执行")
		}
	}
}

func Test隐藏文件不进包(t *testing.T) {
	dir := t.TempDir()
	write(t, filepath.Join(dir, "SKILL.md"), "# Demo\n", 0o644)
	write(t, filepath.Join(dir, ".skillmesh-install"), "https://x\n", 0o644)

	artifact, err := Pack(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range artifact.Manifest {
		if entry.Path == ".skillmesh-install" {
			t.Fatal("本机标记文件不该进包")
		}
	}
}

func Test空目录报错(t *testing.T) {
	if _, err := Pack(t.TempDir()); err == nil {
		t.Fatal("期望空目录报错")
	}
}
