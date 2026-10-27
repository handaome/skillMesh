package cmd

import "testing"

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
