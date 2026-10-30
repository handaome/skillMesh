package cmd

import (
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/packager"
)

// 增量上传只传变了的文件：没变的沿用云端那份，删掉的要报出来
// （设计文档 §6.2「未变化文件不重复传输」）。
func Test增量上传只传变了的文件(t *testing.T) {
	local := packager.Artifact{Manifest: []packager.Entry{
		{Path: "SKILL.md", SHA256: "aaa"},
		{Path: "guide.md", SHA256: "bbb"},
		{Path: "new.md", SHA256: "ccc"},
	}}
	cloud := map[string]string{
		"SKILL.md": "aaa", // 没变
		"guide.md": "old", // 变了
		"gone.md":  "zzz", // 本地删掉了
	}

	changed := changedFiles(local, cloud)
	got := map[string]bool{}
	for _, item := range changed {
		got[item.Path] = true
	}
	if len(changed) != 2 || !got["guide.md"] || !got["new.md"] {
		t.Fatalf("该传的是 guide.md 与 new.md，实际 %v", got)
	}

	deleted := deletedFiles(local, cloud)
	if len(deleted) != 1 || deleted[0] != "gone.md" {
		t.Fatalf("该报出来的是 gone.md，实际 %v", deleted)
	}
}

// 云端没有这个文件就是新增，内容一样的不用传。
func Test增量上传认得出新增的文件(t *testing.T) {
	local := packager.Artifact{Manifest: []packager.Entry{
		{Path: "SKILL.md", SHA256: "aaa"},
	}}
	changed := changedFiles(local, map[string]string{})
	if len(changed) != 1 || changed[0].Path != "SKILL.md" {
		t.Fatalf("云端没有的文件都算新增，实际 %v", changed)
	}
}
