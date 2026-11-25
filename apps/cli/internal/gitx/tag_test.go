package gitx

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// gitIn 在 dir 里跑 git，返回输出。
func gitIn(t *testing.T, dir string, args ...string) string {
	t.Helper()
	cmd := exec.Command("git", args...)
	cmd.Dir = dir
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("git %v 失败: %v\n%s", args, err, out)
	}
	return strings.TrimSpace(string(out))
}

// newRepo 建一个带一次提交与一个注解标签的仓库，返回目录与 commit。
func newRepo(t *testing.T) (string, string) {
	t.Helper()
	dir := t.TempDir()
	gitIn(t, dir, "init", "--quiet")
	gitIn(t, dir, "config", "user.email", "t@example.com")
	gitIn(t, dir, "config", "user.name", "t")
	if err := os.WriteFile(filepath.Join(dir, "a.txt"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	gitIn(t, dir, "add", ".")
	gitIn(t, dir, "commit", "--quiet", "-m", "c1")
	gitIn(t, dir, "tag", "-a", "v1.0.0", "-m", "release")
	gitIn(t, dir, "tag", "light")
	return dir, gitIn(t, dir, "rev-parse", "HEAD")
}

// 注解 tag 有两个对象：tag 自己与它指向的 commit。
// ls-remote 默认只给 tag 对象的 SHA，拿它跟本地记的 commit 比永远不相等——
// 检查更新就会一直报「有新版」。所以 tag 要取剥开的 refs/tags/<tag>^{}。
func TestLsRemotePeelsAnnotatedTag(t *testing.T) {
	dir, commit := newRepo(t)
	branch := gitIn(t, dir, "rev-parse", "--abbrev-ref", "HEAD")

	for _, tag := range []string{"v1.0.0", "light"} {
		got, kind, err := LsRemote(context.Background(), dir, tag)
		if err != nil {
			t.Fatalf("LsRemote(%q) 失败: %v", tag, err)
		}
		if kind != "tag" {
			t.Fatalf("LsRemote(%q) 的类型应该是 tag，实际 %q", tag, kind)
		}
		if got != commit {
			t.Fatalf("LsRemote(%q) 要返回 commit %s，实际 %s", tag, commit, got)
		}
	}

	got, kind, err := LsRemote(context.Background(), dir, branch)
	if err != nil {
		t.Fatalf("LsRemote(%q) 失败: %v", branch, err)
	}
	if kind != "branch" || got != commit {
		t.Fatalf("LsRemote(%q) 应该是分支 %s，实际 %s/%s", branch, commit, kind, got)
	}
}

// 浅克隆不落本地 refs/tags/，查本地 ref 问不出是标签还是分支；
// git 把这次取到的是分支还是标签写在 .git/FETCH_HEAD 里。
func TestFetchKindReadsFetchHead(t *testing.T) {
	dir, _ := newRepo(t)
	branch := gitIn(t, dir, "rev-parse", "--abbrev-ref", "HEAD")

	dest := filepath.Join(t.TempDir(), "repo")
	if _, err := Clone(context.Background(), dir, "v1.0.0", dest); err != nil {
		t.Fatalf("克隆标签失败: %v", err)
	}
	if kind := FetchKind(dest); kind != "tag" {
		t.Fatalf("取标签时应记为 tag，实际 %q", kind)
	}

	dest2 := filepath.Join(t.TempDir(), "repo")
	if _, err := Clone(context.Background(), dir, branch, dest2); err != nil {
		t.Fatalf("克隆分支失败: %v", err)
	}
	if kind := FetchKind(dest2); kind != "branch" {
		t.Fatalf("取分支时应记为 branch，实际 %q", kind)
	}
}
