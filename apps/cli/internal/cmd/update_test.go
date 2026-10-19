package cmd

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
)

func makeRepo(t *testing.T, content string) (path, commit string) {
	t.Helper()
	dir := t.TempDir()
	runGit(t, dir, "init", "-q")
	write(t, filepath.Join(dir, "SKILL.md"), content)
	runGit(t, dir, "add", "-A")
	runGit(t, dir, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init")
	return dir, runGit(t, dir, "rev-parse", "HEAD")
}

func write(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func runGit(t *testing.T, dir string, args ...string) string {
	t.Helper()
	cmd := exec.Command("git", args...)
	cmd.Dir = dir
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("git %v 失败: %v\n%s", args, err, out)
	}
	return strings.TrimSpace(string(out))
}

func Test无变更时不报告更新(t *testing.T) {
	repo, commit := makeRepo(t, "# v1\n")
	lock := lockfile.Lock{Skills: []lockfile.LockedSkill{{
		Name: "demo", Repository: repo, ResolvedCommit: commit,
	}}}

	updates, err := CheckUpdates(context.Background(), lock)
	if err != nil {
		t.Fatal(err)
	}
	if len(updates) != 0 {
		t.Fatalf("期望无更新，得到 %+v", updates)
	}
}

func Test上游有新提交时报告更新(t *testing.T) {
	repo, first := makeRepo(t, "# v1\n")
	write(t, filepath.Join(repo, "SKILL.md"), "# v2\n")
	runGit(t, repo, "add", "-A")
	runGit(t, repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "v2")
	second := runGit(t, repo, "rev-parse", "HEAD")

	lock := lockfile.Lock{Skills: []lockfile.LockedSkill{{
		Name: "demo", Repository: repo, ResolvedCommit: first,
	}}}
	updates, err := CheckUpdates(context.Background(), lock)
	if err != nil {
		t.Fatal(err)
	}
	if len(updates) != 1 {
		t.Fatalf("期望 1 条更新，得到 %+v", updates)
	}
	if updates[0].RemoteCommit != second {
		t.Fatalf("远端 commit 不对：得到 %s，期望 %s", updates[0].RemoteCommit, second)
	}
	if updates[0].LocalCommit != first || updates[0].Name != "demo" {
		t.Fatalf("更新信息不对: %+v", updates[0])
	}
}

func Test同一仓库只查一次远端(t *testing.T) {
	repo, first := makeRepo(t, "# v1\n")
	write(t, filepath.Join(repo, "SKILL.md"), "# v2\n")
	runGit(t, repo, "add", "-A")
	runGit(t, repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "v2")

	// 一个仓库里 --all 装了几十个 Skill，远端 commit 只有一个，
	// 不该每个 Skill 都发一次 ls-remote。
	var calls int
	original := lsRemote
	lsRemote = func(ctx context.Context, repository, ref string) (string, error) {
		calls++
		return original(ctx, repository, ref)
	}
	defer func() { lsRemote = original }()

	var skills []lockfile.LockedSkill
	for _, name := range []string{"a", "b", "c", "d", "e"} {
		skills = append(skills, lockfile.LockedSkill{
			Name: name, Repository: repo, ResolvedCommit: first,
		})
	}
	updates, err := CheckUpdates(context.Background(), lockfile.Lock{Skills: skills})
	if err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Fatalf("同一仓库应只查 1 次，实际查了 %d 次", calls)
	}
	if len(updates) != 5 {
		t.Fatalf("期望 5 条更新，得到 %d", len(updates))
	}
}

func Test不同仓库分别查(t *testing.T) {
	repoA, firstA := makeRepo(t, "# a\n")
	repoB, firstB := makeRepo(t, "# b\n")

	var calls int
	original := lsRemote
	lsRemote = func(ctx context.Context, repository, ref string) (string, error) {
		calls++
		return original(ctx, repository, ref)
	}
	defer func() { lsRemote = original }()

	lock := lockfile.Lock{Skills: []lockfile.LockedSkill{
		{Name: "a", Repository: repoA, ResolvedCommit: firstA},
		{Name: "b", Repository: repoB, ResolvedCommit: firstB},
	}}
	if _, err := CheckUpdates(context.Background(), lock); err != nil {
		t.Fatal(err)
	}
	if calls != 2 {
		t.Fatalf("两个仓库应查 2 次，实际 %d 次", calls)
	}
}
