package cmd

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/installer"
)

// 目标目录已经有一个不受 SkillMesh 管理的同名目录时，默认不许覆盖：
// 那多半是用户自己写的 Skill，静默盖掉就没了（设计文档 §4.3 INST-001）。
func Test同名目录不受管时默认拒绝覆盖(t *testing.T) {
	target := filepath.Join(t.TempDir(), "demo")
	if err := os.MkdirAll(target, 0o755); err != nil {
		t.Fatal(err)
	}
	// 用户自己写的目录：没有安装标记。

	err := CheckTarget(target, "skillmesh:abc-123", false)
	if err == nil {
		t.Fatal("同名目录不受管时不该直接覆盖")
	}
	var conflict *installer.TargetConflictError
	if !errors.As(err, &conflict) {
		t.Fatalf("该报目标冲突，实际 %v", err)
	}
	if conflict.Reason != installer.ReasonUnmanaged {
		t.Fatalf("冲突原因 = %q，期望 %q", conflict.Reason, installer.ReasonUnmanaged)
	}
	// 提示里要说得清还能怎么办。
	if !strings.Contains(err.Error(), "--replace") {
		t.Fatalf("提示里该说清 --replace，实际 %q", err.Error())
	}

	// 用户确认过就放行。
	if err := CheckTarget(target, "skillmesh:abc-123", true); err != nil {
		t.Fatalf("加了 --replace 还该被拒: %v", err)
	}
}

// 受管的目录里装的是另一个 Skill 时也不能覆盖：那是另一个东西。
func Test受管目录里是别的Skill时拒绝覆盖(t *testing.T) {
	target := filepath.Join(t.TempDir(), "demo")
	if err := os.MkdirAll(target, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := installer.MarkInstalled(target, "skillmesh:other-id"); err != nil {
		t.Fatal(err)
	}

	err := CheckTarget(target, "skillmesh:abc-123", false)
	var conflict *installer.TargetConflictError
	if !errors.As(err, &conflict) {
		t.Fatalf("该报目标冲突，实际 %v", err)
	}
	if conflict.Reason != installer.ReasonOtherSkill {
		t.Fatalf("冲突原因 = %q，期望 %q", conflict.Reason, installer.ReasonOtherSkill)
	}
	// 同一个 Skill 的同一个标识：可以覆盖（重装自己）。
	if err := CheckTarget(target, "skillmesh:other-id", false); err != nil {
		t.Fatalf("同一个 Skill 该允许覆盖: %v", err)
	}
}
