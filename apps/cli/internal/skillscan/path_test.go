package skillscan

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestWalkFollow链接 链接要跟，但不能跟出包外。
//
// 跟了链接的内容才算进同步模型（设计文档 §6.1）：
// 包内的链接按内容算进去，包外的链接跳过并报一声，断链不算失败。
func TestWalkFollow链接(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	writeFile(t, filepath.Join(root, "inside.txt"), "包内")
	writeFile(t, filepath.Join(root, "target.txt"), "包内目标")
	writeFile(t, filepath.Join(outside, "secret.txt"), "包外")

	// 包内的链接：内容要算进去。
	if err := os.Symlink(filepath.Join(root, "target.txt"), filepath.Join(root, "link.txt")); err != nil {
		t.Skipf("建符号链接失败（Windows 上要开发者模式）: %v", err)
	}
	// 指向包外的链接：跳过并报出来。
	if err := os.Symlink(filepath.Join(outside, "secret.txt"), filepath.Join(root, "escape.txt")); err != nil {
		t.Fatal(err)
	}
	// 断链：跳过，不能让整次遍历失败。
	if err := os.Symlink(filepath.Join(outside, "missing.txt"), filepath.Join(root, "broken.txt")); err != nil {
		t.Fatal(err)
	}

	var files []string
	var escaped []string
	err := WalkFollow(root, func(path string, info os.FileInfo, err error) error {
		if errors.Is(err, ErrOutsideRoot) {
			rel, relErr := filepath.Rel(root, path)
			if relErr != nil {
				rel = path
			}
			escaped = append(escaped, filepath.ToSlash(rel))
			return nil
		}
		if err != nil {
			return err
		}
		if info.Mode().IsRegular() {
			rel, relErr := filepath.Rel(root, path)
			if relErr != nil {
				rel = path
			}
			files = append(files, filepath.ToSlash(rel))
		}
		return nil
	})
	if err != nil {
		t.Fatalf("遍历失败: %v", err)
	}
	for _, want := range []string{"inside.txt", "target.txt", "link.txt"} {
		if !contains(files, want) {
			t.Errorf("包里少了 %q，实际是 %v", want, files)
		}
	}
	for _, unwanted := range []string{"escape.txt", "broken.txt", "secret.txt"} {
		if contains(files, unwanted) {
			t.Errorf("包里不该有 %q，实际是 %v", unwanted, files)
		}
	}
	if len(escaped) != 1 || !strings.HasSuffix(escaped[0], "escape.txt") {
		t.Errorf("越界的链接没报出来: %v", escaped)
	}
}

// TestWalkFollow链接成环 链接成环时遍历要收场，不能一直展开。
func TestWalkFollow链接成环(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "d")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	writeFile(t, filepath.Join(dir, "f.txt"), "内容")
	if err := os.Symlink(dir, filepath.Join(dir, "loop")); err != nil {
		t.Skipf("建符号链接失败（Windows 上要开发者模式）: %v", err)
	}

	count := 0
	err := WalkFollow(root, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		count++
		return nil
	})
	if err != nil {
		t.Fatalf("遍历失败: %v", err)
	}
	if count > 20 {
		t.Fatalf("遍历没收住：回调了 %d 次", count)
	}
}

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func contains(list []string, value string) bool {
	for _, item := range list {
		if item == value {
			return true
		}
	}
	return false
}
