package cmd

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// 设计文档 §6.3 的冲突表：两边都改时能按文件合就合，合不了才报冲突。
func Test按文件合并决策(t *testing.T) {
	base := map[string]string{"a.md": "A", "b.md": "B", "c.md": "C"}
	local := map[string]string{"a.md": "A-LOCAL", "b.md": "B-LOCAL", "c.md": "C"}
	cloud := map[string]string{"a.md": "A-CLOUD", "b.md": "B", "c.md": "C"}

	plan := mergePlan(base, local, cloud)
	if plan["a.md"] != actionMergeLocal {
		t.Errorf("同一个文件两边都改应尝试合并，得到 %s", plan["a.md"])
	}
	if plan["b.md"] != actionKeepLocal {
		t.Errorf("只有本机改过应保留本机，得到 %s", plan["b.md"])
	}
	if _, touched := plan["c.md"]; touched {
		t.Errorf("两边都没动的文件不该进计划：%v", plan)
	}
}

func Test云端新增的文件会被拉下来(t *testing.T) {
	base := map[string]string{"a.md": "A"}
	local := map[string]string{"a.md": "A"}
	cloud := map[string]string{"a.md": "A", "new.md": "N"}

	plan := mergePlan(base, local, cloud)
	if plan["new.md"] != actionTakeCloud {
		t.Fatalf("云端新增的文件该被拉下来，得到 %s", plan["new.md"])
	}
}

func Test两边改成一样不进计划(t *testing.T) {
	base := map[string]string{"a.md": "A"}
	same := map[string]string{"a.md": "SAME"}
	if plan := mergePlan(base, same, same); len(plan) != 0 {
		t.Fatalf("两边一致不该产生动作：%v", plan)
	}
}

// 真跑一遍三方合并：同一文件、改的是不同行 → 自动合；改同一行 → 判冲突。
func Test同一文件的三方合并(t *testing.T) {
	root := t.TempDir()
	baseDir := filepath.Join(root, "base")
	cloudDir := filepath.Join(root, "cloud")
	localDir := filepath.Join(root, "local")
	for _, dir := range []string{baseDir, cloudDir, localDir} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	write := func(dir, content string) {
		if err := os.WriteFile(filepath.Join(dir, "SKILL.md"), []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}

	// 基线
	write(baseDir, "line1\nline2\nline3\n")
	write(cloudDir, "line1\nline2\nline3\n云端加的\n")
	write(localDir, "line1\n本机改的\nline2\nline3\n")

	ok, err := mergeFile(baseDir, localDir, cloudDir, "SKILL.md")
	if err != nil || !ok {
		t.Fatalf("改的是不同位置，应该合得上：%v %v", ok, err)
	}
	merged, err := os.ReadFile(filepath.Join(localDir, "SKILL.md"))
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"本机改的", "云端加的"} {
		if !strings.Contains(string(merged), want) {
			t.Errorf("合并结果应含 %q：\n%s", want, merged)
		}
	}

	// 同一个位置改成不同内容 → 冲突，不写盘
	write(cloudDir, "line1\nline2\n云端改的\nline3\n")
	write(localDir, "line1\nline2\n本机改的\nline3\n")
	before, _ := os.ReadFile(filepath.Join(localDir, "SKILL.md"))
	ok, err = mergeFile(baseDir, localDir, cloudDir, "SKILL.md")
	if err != nil {
		t.Fatal(err)
	}
	if ok {
		t.Fatal("同一行改成不同内容应判冲突")
	}
	after, _ := os.ReadFile(filepath.Join(localDir, "SKILL.md"))
	if string(before) != string(after) {
		t.Fatal("判冲突时不该改动本地文件")
	}
}

func Test基线文件缺失时不硬合(t *testing.T) {
	root := t.TempDir()
	local := filepath.Join(root, "local")
	cloud := filepath.Join(root, "cloud")
	for _, dir := range []string{local, cloud} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	content := []byte("a\nb\n")
	if err := os.WriteFile(filepath.Join(local, "SKILL.md"), content, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(cloud, "SKILL.md"), []byte("x\ny\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	ok, err := mergeFile(filepath.Join(root, "no-such-base"), local, cloud, "SKILL.md")
	if err != nil {
		t.Fatalf("基线缺失不该报错：%v", err)
	}
	if ok {
		t.Fatal("没有基线可比，合不了才对")
	}
}
