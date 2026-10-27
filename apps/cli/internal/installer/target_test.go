package installer

import (
	"os"
	"path/filepath"
	"testing"
)

// 目标形态判错的后果不一样：把符号链接当目录就会替换掉用户自己的链接。
func TestClassifyTarget(t *testing.T) {
	dir := t.TempDir()

	state, err := ClassifyTarget(filepath.Join(dir, "not-there"))
	if err != nil {
		t.Fatalf("分类不该报错: %v", err)
	}
	if state != TargetAbsent {
		t.Fatalf("不存在的路径应是 TargetAbsent，实际 %v", state)
	}

	state, err = ClassifyTarget(dir)
	if err != nil {
		t.Fatalf("分类不该报错: %v", err)
	}
	if state != TargetDir {
		t.Fatalf("目录应是 TargetDir，实际 %v", state)
	}

	file := filepath.Join(dir, "a-file")
	if err := os.WriteFile(file, []byte("x"), 0o644); err != nil {
		t.Fatalf("写文件失败: %v", err)
	}
	state, err = ClassifyTarget(file)
	if err != nil {
		t.Fatalf("分类不该报错: %v", err)
	}
	if state != TargetFile {
		t.Fatalf("文件应是 TargetFile，实际 %v", state)
	}
}

func TestClassifyTargetSymlink(t *testing.T) {
	dir := t.TempDir()
	link := filepath.Join(dir, "link")
	if err := os.Symlink(dir, link); err != nil {
		t.Skipf("本机建不了符号链接，跳过: %v", err)
	}
	state, err := ClassifyTarget(link)
	if err != nil {
		t.Fatalf("分类不该报错: %v", err)
	}
	if state != TargetSymlink {
		t.Fatalf("符号链接应是 TargetSymlink，实际 %v", state)
	}
}

func TestMarkerAndSkillID(t *testing.T) {
	dir := t.TempDir()
	if got := MarkerOf(dir); got != "" {
		t.Fatalf("没装过的目录不该有标记，实际 %q", got)
	}
	if err := MarkInstalled(dir, "skillmesh:abc-123"); err != nil {
		t.Fatalf("写标记失败: %v", err)
	}
	if got := MarkerOf(dir); got != "skillmesh:abc-123" {
		t.Fatalf("标记内容 = %q", got)
	}
	if got := SkillIDOf(dir); got != "abc-123" {
		t.Fatalf("Skill ID = %q，期望 abc-123", got)
	}

	gitDir := t.TempDir()
	if err := MarkInstalled(gitDir, "https://github.com/example/demo"); err != nil {
		t.Fatalf("写标记失败: %v", err)
	}
	if got := SkillIDOf(gitDir); got != "" {
		t.Fatalf("Git 来源的目录不该有 Skill ID，实际 %q", got)
	}
}
