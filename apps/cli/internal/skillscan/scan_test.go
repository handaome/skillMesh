package skillscan

import (
	"os"
	"path/filepath"
	"testing"
)

// writeSkill 造一个含 SKILL.md 的技能目录。
func writeSkill(t *testing.T, dir, name string) {
	t.Helper()
	path := filepath.Join(dir, name)
	if err := os.MkdirAll(path, 0o755); err != nil {
		t.Fatal(err)
	}
	content := "---\nname: " + name + "\n---\n\n说明\n"
	if err := os.WriteFile(filepath.Join(path, SkillFile), []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

// 根目录本身是链接时也要扫得到里面的技能。
//
// 多个 Agent 共用一个技能目录就是这个形状：目录是指到实体目录的链接。
// filepath.Walk 站在根上看的是 Lstat，一步都进不去——扫出来会是空的，
// 本地看起来「一个技能都没有」，再叠上删除传播就会把云端清空。
func TestCandidatesFollowsLinkedRoot(t *testing.T) {
	base := t.TempDir()
	real := filepath.Join(base, "real")
	if err := os.MkdirAll(real, 0o755); err != nil {
		t.Fatal(err)
	}
	writeSkill(t, real, "demo")
	link := filepath.Join(base, "link")
	if err := LinkDir(real, link); err != nil {
		t.Skipf("建不了目录链接: %v", err)
	}
	found, err := Candidates(link, true)
	if err != nil {
		t.Fatal(err)
	}
	if len(found) != 1 || found[0].Name != "demo" {
		t.Fatalf("扫到 %v，want 一条 demo", found)
	}
	// 候选路径按链接那边拼：用户看到的是链接路径，基线和日志都记它。
	want := filepath.Join(link, "demo")
	if found[0].Dir != want {
		t.Fatalf("候选路径 %s，want %s", found[0].Dir, want)
	}
}

// 根目录是实体目录时不受影响。
func TestCandidatesPlainRoot(t *testing.T) {
	base := t.TempDir()
	writeSkill(t, base, "demo")
	found, err := Candidates(base, true)
	if err != nil {
		t.Fatal(err)
	}
	if len(found) != 1 || found[0].Dir != filepath.Join(base, "demo") {
		t.Fatalf("扫到 %v，want base/demo", found)
	}
}

// 链接指向的技能目录：候选路径是链接本身，不往里挖。
func TestCandidatesLinkedSkillDir(t *testing.T) {
	base := t.TempDir()
	library := filepath.Join(base, "library")
	if err := os.MkdirAll(library, 0o755); err != nil {
		t.Fatal(err)
	}
	writeSkill(t, library, "demo")
	agentDir := filepath.Join(base, "agent")
	if err := os.MkdirAll(agentDir, 0o755); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(agentDir, "demo")
	if err := LinkDir(filepath.Join(library, "demo"), link); err != nil {
		t.Skipf("建不了目录链接: %v", err)
	}
	found, err := Candidates(agentDir, true)
	if err != nil {
		t.Fatal(err)
	}
	if len(found) != 1 || found[0].Dir != link {
		t.Fatalf("扫到 %v，want %s", found, link)
	}
}

// ResolveLink 一层层解开链接。
func TestResolveLinkNested(t *testing.T) {
	base := t.TempDir()
	real := filepath.Join(base, "real")
	if err := os.MkdirAll(real, 0o755); err != nil {
		t.Fatal(err)
	}
	first := filepath.Join(base, "first")
	second := filepath.Join(base, "second")
	if err := LinkDir(real, first); err != nil {
		t.Skipf("建不了目录链接: %v", err)
	}
	if err := LinkDir(first, second); err != nil {
		t.Skipf("建不了目录链接: %v", err)
	}
	if got := ResolveLink(second); got != real {
		t.Fatalf("解到 %s，want %s", got, real)
	}
}

// 不是链接时原样返回。
func TestResolveLinkPlainPath(t *testing.T) {
	base := t.TempDir()
	if got := ResolveLink(base); got != base {
		t.Fatalf("解到 %s，want %s", got, base)
	}
}
