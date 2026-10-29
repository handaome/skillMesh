package cmd

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
)

// writeTestSkill 造一个含 SKILL.md 的技能目录。
func writeTestSkill(t *testing.T, dir, name, body string) {
	t.Helper()
	path := filepath.Join(dir, name)
	if err := os.MkdirAll(path, 0o755); err != nil {
		t.Fatal(err)
	}
	content := "---\nname: " + name + "\n---\n\n" + body + "\n"
	if err := os.WriteFile(filepath.Join(path, skillscan.SkillFile), []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

// 源目录没有的搬过去，内容一样的删掉目标目录里那份。
func TestPlanShareMovesAndDrops(t *testing.T) {
	from := t.TempDir()
	to := t.TempDir()
	writeTestSkill(t, from, "same", "一样的")
	writeTestSkill(t, to, "same", "一样的")
	writeTestSkill(t, to, "only-in-target", "只有目标有")
	plan, err := planShare(from, to)
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.move) != 1 || plan.move[0] != "only-in-target" {
		t.Fatalf("要搬 %v，want only-in-target", plan.move)
	}
	if len(plan.drop) != 1 || plan.drop[0] != "same" {
		t.Fatalf("要删 %v，want same", plan.drop)
	}
	if len(plan.conflicts) != 0 {
		t.Fatalf("冲突 %v，want 空", plan.conflicts)
	}
}

// 源目录有、目标目录没有的：建链接。
func TestPlanSharePlansLinks(t *testing.T) {
	from := t.TempDir()
	to := t.TempDir()
	writeTestSkill(t, from, "extra", "只有源目录有")
	plan, err := planShare(from, to)
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.link) != 1 || plan.link[0] != "extra" {
		t.Fatalf("要建链接 %v，want extra", plan.link)
	}
}

// 同名但内容不同：算冲突，不动。
func TestPlanShareReportsConflict(t *testing.T) {
	from := t.TempDir()
	to := t.TempDir()
	writeTestSkill(t, from, "demo", "源的内容")
	writeTestSkill(t, to, "demo", "目标的内容")
	plan, err := planShare(from, to)
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.conflicts) != 1 || plan.conflicts[0] != "demo" {
		t.Fatalf("冲突 %v，want demo", plan.conflicts)
	}
}

// 目标目录还没有：直接建链接就行。
func TestPlanShareMissingTarget(t *testing.T) {
	from := t.TempDir()
	to := filepath.Join(t.TempDir(), "not-exist")
	plan, err := planShare(from, to)
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.move)+len(plan.drop)+len(plan.link) != 0 {
		t.Fatalf("计划 %v，want 空", plan)
	}
}

// 不是技能的东西（比如 Agent 自己的 .system）原样留着。
func TestPlanShareKeepsLeftovers(t *testing.T) {
	from := t.TempDir()
	to := t.TempDir()
	if err := os.WriteFile(filepath.Join(to, "README.md"), []byte("说明"), 0o644); err != nil {
		t.Fatal(err)
	}
	plan, err := planShare(from, to)
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.leftovers) != 0 {
		t.Fatalf("剩余 %v，want 不算进计划", plan.leftovers)
	}
}

// 共享之后两边看到的是同一份内容：目标目录里的技能变成链接。
func TestApplyShareLinksSkills(t *testing.T) {
	from := t.TempDir()
	to := filepath.Join(t.TempDir(), "to")
	if err := os.MkdirAll(to, 0o755); err != nil {
		t.Fatal(err)
	}
	writeTestSkill(t, from, "demo", "内容")
	writeTestSkill(t, to, "demo", "内容")
	writeTestSkill(t, from, "extra", "多出来的")
	plan, err := planShare(from, to)
	if err != nil {
		t.Fatal(err)
	}
	if err := applyShare(from, to, plan); err != nil {
		t.Fatal(err)
	}
	// 目标目录里的 demo 是链接，指向源目录里那一份。
	link := filepath.Join(to, "demo")
	target, ok := skillscan.LinkTarget(link)
	if !ok {
		t.Fatalf("%s 不是链接", link)
	}
	if skillscan.ResolveLink(target) != filepath.Join(from, "demo") {
		t.Fatalf("链接指向 %s，want %s", target, filepath.Join(from, "demo"))
	}
	// 源目录有、目标目录没有的，也补上了链接。
	if _, ok := skillscan.LinkTarget(filepath.Join(to, "extra")); !ok {
		t.Fatal("extra 没建链接")
	}
	// 内容还在，读得到。
	if _, err := os.Stat(filepath.Join(to, "demo", skillscan.SkillFile)); err != nil {
		t.Fatalf("链接那边读不到技能: %v", err)
	}
}

// Agent 自己的目录（.system 之类）不能被搬走。
func TestApplyShareKeepsAgentOwnDirs(t *testing.T) {
	from := t.TempDir()
	to := filepath.Join(t.TempDir(), "to")
	if err := os.MkdirAll(filepath.Join(to, ".system"), 0o755); err != nil {
		t.Fatal(err)
	}
	writeTestSkill(t, from, "demo", "内容")
	plan, err := planShare(from, to)
	if err != nil {
		t.Fatal(err)
	}
	if err := applyShare(from, to, plan); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(to, ".system")); err != nil {
		t.Fatalf(".system 不见了: %v", err)
	}
}
