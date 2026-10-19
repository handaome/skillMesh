package cmd

import (
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
)

func Test注册中心安装写进锁文件(t *testing.T) {
	root := t.TempDir()
	target := apiclient.SkillVersion{
		Version:  "1.2.0",
		Artifact: &apiclient.ArtifactSummary{ID: "art-1", SHA256: "abc123"},
	}
	if err := recordInstall(root, "demo", "skill-1", target, "user", []string{"claude-code"}); err != nil {
		t.Fatal(err)
	}

	lock, err := lockfile.Load(root)
	if err != nil {
		t.Fatal(err)
	}
	entry, ok := lock.Find("demo")
	if !ok {
		t.Fatalf("锁文件里没有 demo：%+v", lock.Skills)
	}
	if entry.SourceID != "skill-1" || entry.Version != "1.2.0" || entry.SHA256 != "abc123" {
		t.Fatalf("锁文件记录不对: %+v", entry)
	}
	if len(entry.Agents) != 1 || entry.Agents[0] != "claude-code" {
		t.Fatalf("Agent 没记上: %+v", entry.Agents)
	}
}

func TestGit来源看commit注册中心看版本号(t *testing.T) {
	git := resolvedLabel(lockfile.LockedSkill{ResolvedCommit: "0123456789abcdef"})
	if git != "01234567" {
		t.Fatalf("Git 来源应显示短 commit，得到 %q", git)
	}
	registry := resolvedLabel(lockfile.LockedSkill{Version: "1.2.0"})
	if registry != "1.2.0" {
		t.Fatalf("注册中心来源应显示版本号，得到 %q", registry)
	}
}
