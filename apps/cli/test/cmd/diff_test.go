package cmd_test

import (
	cmd "github.com/handaome/skillmesh/apps/cli/internal/cmd"
	"os"
	"path/filepath"
	"testing"
)

func Test差异分出新增修改删除(t *testing.T) {
	local := t.TempDir()
	remote := t.TempDir()
	writeDiffFile(t, local, "SKILL.md", "旧的一版\n")
	writeDiffFile(t, local, "gone.md", "要被删掉\n")
	writeDiffFile(t, remote, "SKILL.md", "新的一版\n多了一行\n")
	writeDiffFile(t, remote, "added.md", "新文件\n")

	changes, err := cmd.DiffTrees(local, remote)
	if err != nil {
		t.Fatal(err)
	}
	kinds := map[string]string{}
	for _, change := range changes {
		kinds[change.Path] = change.Kind
		if change.Path != "SKILL.md" {
			continue
		}
		// 一行删掉、两行加上
		if change.Added != 2 || change.Removed != 1 {
			t.Fatalf("行数不对: +%d/-%d", change.Added, change.Removed)
		}
	}
	if kinds["added.md"] != "新增" || kinds["gone.md"] != "删除" || kinds["SKILL.md"] != "修改" {
		t.Fatalf("分类不对: %+v", kinds)
	}
}

func Test相同内容没有差异(t *testing.T) {
	dir := t.TempDir()
	writeDiffFile(t, dir, "SKILL.md", "一样的\n")
	other := t.TempDir()
	writeDiffFile(t, other, "SKILL.md", "一样的\n")

	changes, err := cmd.DiffTrees(dir, other)
	if err != nil {
		t.Fatal(err)
	}
	if len(changes) != 0 {
		t.Fatalf("同样的内容不该有差异: %+v", changes)
	}
}

func Test二进制文件只报文件级(t *testing.T) {
	local := t.TempDir()
	remote := t.TempDir()
	writeDiffFile(t, local, "a.bin", "old\x00data")
	writeDiffFile(t, remote, "a.bin", "new\x00bytes")

	changes, err := cmd.DiffTrees(local, remote)
	if err != nil {
		t.Fatal(err)
	}
	if len(changes) != 1 || !changes[0].Truncated {
		t.Fatalf("二进制只该报文件级: %+v", changes)
	}
}

func Test本地改动看得出(t *testing.T) {
	dir := t.TempDir()
	writeDiffFile(t, dir, "SKILL.md", "装的时候是这样\n")

	digest := cmd.InstalledDigest(dir)

	if cmd.ContentChanged(dir, digest) {
		t.Fatal("刚装完不该算本地改动")
	}
	writeDiffFile(t, dir, "SKILL.md", "我自己改了\n")
	if !cmd.ContentChanged(dir, digest) {
		t.Fatal("改了内容就该判定为本地改动")
	}
	// 老锁文件没有这个字段，不该拦着用户
	if cmd.ContentChanged(dir, "") {
		t.Fatal("没有 installed_hash 时不该拦")
	}
}

func writeDiffFile(t *testing.T, dir, name, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(filepath.Join(dir, name)), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, name), []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}
