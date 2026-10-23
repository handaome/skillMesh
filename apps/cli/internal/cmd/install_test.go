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
	if err := recordInstall(root, "demo", "skill-1", target, "user", []string{"claude-code"}, "hash-1"); err != nil {
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
	if entry.SourceID != "skill-1" || entry.Version != "1.2.0" || entry.SHA256 != "abc123" ||
		entry.InstalledHash != "hash-1" {
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

// 版本列表按创建时间倒序排，「列表里第一个」不是「最新」：
// 刚发出来的旧版本号会被当成最新装上。
func Test装最新按版本号取而不是列表顺序(t *testing.T) {
	versions := apiclient.VersionPage{Items: []apiclient.SkillVersion{
		{Version: "1.9.0", Status: "published", Artifact: &apiclient.ArtifactSummary{ID: "a1"}},
		{Version: "1.10.0", Status: "published", Artifact: &apiclient.ArtifactSummary{ID: "a2"}},
	}}

	selected, err := selectVersion(versions, "", false, lockfile.LockedSkill{}, false, "")
	if err != nil {
		t.Fatal(err)
	}
	if selected.Version != "1.10.0" {
		t.Fatalf("应取版本号最大的 1.10.0，得到 %s", selected.Version)
	}

	// 注册中心给了 latest_version 就以它为准。
	selected, err = selectVersion(versions, "", false, lockfile.LockedSkill{}, false, "1.9.0")
	if err != nil {
		t.Fatal(err)
	}
	if selected.Version != "1.9.0" {
		t.Fatalf("应按注册中心的 latest_version 装 1.9.0，得到 %s", selected.Version)
	}
}

func Test版本号比较与服务端一致(t *testing.T) {
	cases := []struct {
		candidate, current string
		want               bool
	}{
		{"1.10.0", "1.9.0", true},
		{"1.9.0", "1.10.0", false},
		{"1.0", "1.0.0", false},
		{"1.0.1", "1.0", true},
		{"1.0.beta", "1.0.alpha", true},
	}
	for _, c := range cases {
		if got := versionGreater(c.candidate, c.current); got != c.want {
			t.Fatalf("versionGreater(%q, %q) = %v，期望 %v",
				c.candidate, c.current, got, c.want)
		}
	}
}
