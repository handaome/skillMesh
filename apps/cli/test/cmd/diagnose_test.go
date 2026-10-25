package cmd_test

import (
	"os"
	"path/filepath"
	"testing"

	cmd "github.com/handaome/skillmesh/apps/cli/internal/cmd"
)

// doctor 要能诊断「目录权限」（设计文档 §13）：目录在但写不进去，
// 装 Skill 会在最后一步才失败——那时候用户已经等完一轮下载了。
func TestDoctor报目录能不能写(t *testing.T) {
	dir := t.TempDir()
	if got := cmd.DirState(dir); got != "存在，可写" {
		t.Fatalf("可写目录该报「存在，可写」，实际 %q", got)
	}

	missing := filepath.Join(dir, "not-here")
	if got := cmd.DirState(missing); got != "不存在，安装时创建" {
		t.Fatalf("不存在的目录该报「不存在，安装时创建」，实际 %q", got)
	}
}

// 目录在、但只读时要说「写不进去」，而不是笼统的「存在」。
func TestDoctor报出只读目录(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root 能写只读目录，跳过")
	}
	dir := t.TempDir()
	ro := filepath.Join(dir, "ro")
	if err := os.Mkdir(ro, 0o500); err != nil {
		t.Fatal(err)
	}
	// Windows 上目录权限位不管用，只读靠 ACL——那边跳过。
	probe, err := os.CreateTemp(ro, ".probe-*")
	if err == nil {
		probe.Close()
		os.Remove(probe.Name())
		t.Skip("该平台上目录权限位不生效")
	}
	if got := cmd.DirState(ro); got != "存在，但写不进去" {
		t.Fatalf("只读目录该报「存在，但写不进去」，实际 %q", got)
	}
}
