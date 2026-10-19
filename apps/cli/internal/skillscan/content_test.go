package skillscan

import (
	"os"
	"path/filepath"
	"testing"
)

func Test目录摘要只跟内容有关(t *testing.T) {
	dir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dir, "references"), 0o755); err != nil {
		t.Fatal(err)
	}
	writeFile(t, filepath.Join(dir, "SKILL.md"), "# demo\n")
	writeFile(t, filepath.Join(dir, "references", "a.md"), "a\n")

	before, err := DirDigest(dir)
	if err != nil {
		t.Fatal(err)
	}
	// 本机状态标记不该影响摘要
	writeFile(t, filepath.Join(dir, ".skillmesh-install"), "repo\n")
	after, err := DirDigest(dir)
	if err != nil {
		t.Fatal(err)
	}
	if before != after {
		t.Fatalf("隐藏文件不该影响摘要：%s vs %s", before, after)
	}
}

func Test内容变了摘要就变(t *testing.T) {
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "SKILL.md"), "# demo\n")
	before, err := DirDigest(dir)
	if err != nil {
		t.Fatal(err)
	}
	writeFile(t, filepath.Join(dir, "SKILL.md"), "# demo 改过了\n")
	after, err := DirDigest(dir)
	if err != nil {
		t.Fatal(err)
	}
	if before == after {
		t.Fatal("内容变了摘要必须跟着变")
	}
}

func Test文件顺序不影响摘要(t *testing.T) {
	files := []FileDigest{{Path: "b.md", SHA256: "22"}, {Path: "a.md", SHA256: "11"}}
	reversed := []FileDigest{{Path: "a.md", SHA256: "11"}, {Path: "b.md", SHA256: "22"}}
	if Digest(files) != Digest(reversed) {
		t.Fatal("摘要不能依赖遍历顺序")
	}
}
