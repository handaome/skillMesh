package gitx

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestNormalizeURL拒绝危险协议(t *testing.T) {
	for _, bad := range []string{
		"file:///etc/passwd",
		"C:\\Windows\\System32",
		"ftp://example.com/x.git",
		"http://example.com/x.git",
	} {
		if _, err := NormalizeURL(bad); err == nil {
			t.Fatalf("期望拒绝 %s", bad)
		}
	}
}

func TestNormalizeURL拒绝内嵌凭据(t *testing.T) {
	_, err := NormalizeURL("https://user:token@example.com/x.git")
	if err == nil || !strings.Contains(err.Error(), "凭据") {
		t.Fatalf("期望拒绝内嵌凭据，得到 %v", err)
	}
}

func TestNormalizeURL接受合法地址(t *testing.T) {
	for _, good := range []string{
		"https://github.com/owner/repo.git",
		"git@github.com:owner/repo.git",
		"ssh://git@github.com/owner/repo.git",
		"https://git.example.com/team/skills.git",
	} {
		got, err := NormalizeURL(good)
		if err != nil {
			t.Fatalf("期望接受 %s，得到 %v", good, err)
		}
		if got == "" {
			t.Fatalf("规范化结果为空: %s", good)
		}
	}
}

func TestClone返回实际commit(t *testing.T) {
	repo := makeRepo(t, "# v1\n")
	commit := git(t, repo, "rev-parse", "HEAD")

	dest := filepath.Join(t.TempDir(), "clone")
	got, err := Clone(context.Background(), repo, "", dest)
	if err != nil {
		t.Fatal(err)
	}
	if got != commit {
		t.Fatalf("commit 不对：得到 %s，期望 %s", got, commit)
	}
}

func TestClone指定commit时检出该版本(t *testing.T) {
	repo := makeRepo(t, "# v1\n")
	first := git(t, repo, "rev-parse", "HEAD")
	writeFile(t, filepath.Join(repo, "SKILL.md"), "# v2\n")
	git(t, repo, "add", "-A")
	git(t, repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "v2")

	dest := filepath.Join(t.TempDir(), "clone")
	got, err := Clone(context.Background(), repo, first, dest)
	if err != nil {
		t.Fatal(err)
	}
	if got != first {
		t.Fatalf("未检出指定 commit：%s", got)
	}
	data, err := os.ReadFile(filepath.Join(dest, "SKILL.md"))
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "# v1\n" {
		t.Fatalf("检出内容不对: %q", string(data))
	}
}

func TestLsRemote取到远端最新commit(t *testing.T) {
	repo := makeRepo(t, "# v1\n")
	first := git(t, repo, "rev-parse", "HEAD")

	got, err := LsRemote(context.Background(), repo, "HEAD")
	if err != nil {
		t.Fatal(err)
	}
	if got != first {
		t.Fatalf("ls-remote 结果不对：%s", got)
	}

	writeFile(t, filepath.Join(repo, "SKILL.md"), "# v2\n")
	git(t, repo, "add", "-A")
	git(t, repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "v2")

	got, err = LsRemote(context.Background(), repo, "HEAD")
	if err != nil {
		t.Fatal(err)
	}
	if got == first {
		t.Fatal("ls-remote 应反映新提交")
	}
}

func makeRepo(t *testing.T, content string) string {
	t.Helper()
	dir := t.TempDir()
	git(t, dir, "init", "-q")
	writeFile(t, filepath.Join(dir, "SKILL.md"), content)
	git(t, dir, "add", "-A")
	git(t, dir, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init")
	return dir
}

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func git(t *testing.T, dir string, args ...string) string {
	t.Helper()
	cmd := exec.Command("git", args...)
	cmd.Dir = dir
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("git %v 失败: %v\n%s", args, err, out)
	}
	return strings.TrimSpace(string(out))
}
