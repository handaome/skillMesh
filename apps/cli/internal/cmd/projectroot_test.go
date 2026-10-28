package cmd

import (
	"os"
	"path/filepath"
	"testing"
)

func TestFindProjectRoot(t *testing.T) {
	base := t.TempDir()
	repo := filepath.Join(base, "repo")
	sub := filepath.Join(repo, "apps", "cli")
	if err := os.MkdirAll(sub, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(repo, ".git"), 0o755); err != nil {
		t.Fatal(err)
	}
	// 子目录里的 skillmesh.yaml 也算项目根。
	marked := filepath.Join(base, "marked", "deep")
	if err := os.MkdirAll(marked, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(base, "marked", "skillmesh.yaml"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	plain := filepath.Join(base, "plain", "sub")
	if err := os.MkdirAll(plain, 0o755); err != nil {
		t.Fatal(err)
	}

	cases := []struct {
		name string
		from string
		want string
	}{
		{name: "向上找到 .git", from: sub, want: repo},
		{name: "向上找到 skillmesh.yaml", from: marked, want: filepath.Join(base, "marked")},
		{name: "什么都没有就用当前目录", from: plain, want: plain},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := findProjectRoot(c.from); got != c.want {
				t.Fatalf("findProjectRoot(%q) = %q, want %q", c.from, got, c.want)
			}
		})
	}
}

func TestSetProjectRoot(t *testing.T) {
	dir := t.TempDir()
	previous := projectRootOverride
	t.Cleanup(func() { projectRootOverride = previous })

	if err := setProjectRoot(dir); err != nil {
		t.Fatalf("指定目录失败: %v", err)
	}
	if projectRootOverride != dir {
		t.Fatalf("项目根 = %q, want %q", projectRootOverride, dir)
	}
	if err := setProjectRoot(filepath.Join(dir, "不存在")); err == nil {
		t.Fatal("路径不存在时应该报错")
	}
	file := filepath.Join(dir, "a.txt")
	if err := os.WriteFile(file, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := setProjectRoot(file); err == nil {
		t.Fatal("路径不是目录时应该报错")
	}
}
