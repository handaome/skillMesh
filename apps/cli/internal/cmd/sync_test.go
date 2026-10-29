package cmd

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
)

func TestDedupeTargets(t *testing.T) {
	targets := []SyncTarget{
		{Name: "archify", Agent: "claude-code", Path: `C:\Users\u\.claude\skills\archify`},
		{Name: "archify", Agent: "claude-code", Path: `C:\Users\u\.config\magpie\library\skills\archify`},
		{Name: "archify", Agent: "codex", Path: `C:\Users\u\.codex\skills\archify`},
		{Name: "slides", Agent: "claude-code", Path: `C:\Users\u\.claude\skills\slides`},
	}
	got := dedupeTargets(targets)
	if len(got) != 3 {
		t.Fatalf("去重后 %d 条，want 3", len(got))
	}
	// 先出现的（Agent 原生目录）优先。
	if got[0].Path != targets[0].Path {
		t.Fatalf("留下的是 %s，want %s", got[0].Path, targets[0].Path)
	}
	// 不同 Agent 的同名 Skill 不算重复。
	if got[1].Agent != "codex" {
		t.Fatalf("第二条是 %s，want codex", got[1].Agent)
	}
}

// 两个 Agent 用同一个目录：同一个 Skill 就是同一份文件，只算一条。
func TestDedupeTargetsSharedDir(t *testing.T) {
	shared := filepath.Join(t.TempDir(), "shared")
	targets := []SyncTarget{
		{Name: "archify", Agent: "claude-code", Path: filepath.Join(shared, "archify")},
		{Name: "archify", Agent: "codex", Path: filepath.Join(shared, "archify")},
	}
	got := dedupeTargets(targets)
	if len(got) != 1 {
		t.Fatalf("去重后 %d 条，want 1", len(got))
	}
}

// Agent 目录是链接指到同一处：按实体路径算是同一份，只留一条。
func TestDedupeTargetsLinkedDir(t *testing.T) {
	base := t.TempDir()
	shared := filepath.Join(base, "shared")
	if err := os.MkdirAll(shared, 0o755); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(base, "link")
	if err := skillscan.LinkDir(shared, link); err != nil {
		t.Skipf("建不了目录链接: %v", err)
	}
	targets := []SyncTarget{
		{Name: "archify", Agent: "claude-code", Path: filepath.Join(shared, "archify")},
		{Name: "archify", Agent: "codex", Path: filepath.Join(link, "archify")},
	}
	got := dedupeTargets(targets)
	if len(got) != 1 {
		t.Fatalf("去重后 %d 条，want 1", len(got))
	}
}
