package cmd

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/syncstate"
)

// dropRoots 在临时目录里造一个扫描根，返回根与「已被删掉」的 Skill 路径。
func dropRoots(t *testing.T) (string, string) {
	t.Helper()
	root := t.TempDir()
	removed := filepath.Join(root, "yuanbao")
	return root, removed
}

// 本地目录没了、云端 id 也记着：这条该往云端传删除。
func TestRemovalCandidatesTakesRemovedSkill(t *testing.T) {
	root, removed := dropRoots(t)
	state := syncstate.State{Skills: []syncstate.Entry{
		{Name: "yuanbao", Agent: "claude-code", SkillID: "id-1", Path: removed},
	}}
	candidates := removalCandidates(state, nil, []scanRoot{{dir: root, agent: "claude-code"}},
		SyncOptions{})
	if len(candidates) != 1 || candidates[0].Name != "yuanbao" {
		t.Fatalf("候选 %v，want 一条 yuanbao", candidates)
	}
}

// 目录还在：不是删除。
func TestRemovalCandidatesKeepsExistingDir(t *testing.T) {
	root, removed := dropRoots(t)
	if err := os.MkdirAll(removed, 0o755); err != nil {
		t.Fatal(err)
	}
	state := syncstate.State{Skills: []syncstate.Entry{
		{Name: "yuanbao", Agent: "claude-code", SkillID: "id-1", Path: removed},
	}}
	candidates := removalCandidates(state, nil, []scanRoot{{dir: root, agent: "claude-code"}},
		SyncOptions{})
	if len(candidates) != 0 {
		t.Fatalf("候选 %v，want 空", candidates)
	}
}

// 本机别处还有同一份（挪了目录、装给别的 Agent）：不算删。
func TestRemovalCandidatesSkipsCopyStillPresent(t *testing.T) {
	root, removed := dropRoots(t)
	state := syncstate.State{Skills: []syncstate.Entry{
		{Name: "yuanbao", Agent: "claude-code", SkillID: "id-1", Path: removed},
	}}
	scanned := []SyncTarget{
		{Name: "yuanbao", Agent: "claude-code", Path: filepath.Join(root, "yuanbao")},
	}
	candidates := removalCandidates(state, scanned, []scanRoot{{dir: root, agent: "claude-code"}},
		SyncOptions{})
	if len(candidates) != 0 {
		t.Fatalf("候选 %v，want 空", candidates)
	}
}

// 没记过云端 id 的：云端没有对应的东西可删，不能碰。
func TestRemovalCandidatesSkipsWithoutSkillID(t *testing.T) {
	root, removed := dropRoots(t)
	state := syncstate.State{Skills: []syncstate.Entry{
		{Name: "yuanbao", Agent: "claude-code", Path: removed},
	}}
	candidates := removalCandidates(state, nil, []scanRoot{{dir: root, agent: "claude-code"}},
		SyncOptions{})
	if len(candidates) != 0 {
		t.Fatalf("候选 %v，want 空", candidates)
	}
}

// 路径不在扫描范围内：范围变了不等于用户删了它。
func TestRemovalCandidatesSkipsOutOfScope(t *testing.T) {
	_, removed := dropRoots(t)
	other := t.TempDir()
	state := syncstate.State{Skills: []syncstate.Entry{
		{Name: "yuanbao", Agent: "claude-code", SkillID: "id-1", Path: removed},
	}}
	candidates := removalCandidates(state, nil, []scanRoot{{dir: other, agent: "claude-code"}},
		SyncOptions{})
	if len(candidates) != 0 {
		t.Fatalf("候选 %v，want 空", candidates)
	}
}

// --skill 只挑那一个。
func TestRemovalCandidatesHonoursOnly(t *testing.T) {
	root, removed := dropRoots(t)
	state := syncstate.State{Skills: []syncstate.Entry{
		{Name: "yuanbao", Agent: "claude-code", SkillID: "id-1", Path: removed},
		{Name: "slides", Agent: "claude-code", SkillID: "id-2",
			Path: filepath.Join(root, "slides")},
	}}
	candidates := removalCandidates(state, nil, []scanRoot{{dir: root, agent: "claude-code"}},
		SyncOptions{Only: "slides"})
	if len(candidates) != 1 || candidates[0].Name != "slides" {
		t.Fatalf("候选 %v，want 一条 slides", candidates)
	}
}
