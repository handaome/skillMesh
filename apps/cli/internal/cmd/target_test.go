package cmd

import (
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/installer"
)

// 目标被占的四种情况要分得清：脚本靠 reason 决定该换目录还是该让用户确认。
func TestCheckTarget(t *testing.T) {
	dir := t.TempDir()

	// 不存在：直接放行。
	if err := CheckTarget(filepath.Join(dir, "not-there"), "skillmesh:abc", false); err != nil {
		t.Fatalf("目标不存在时不该报错: %v", err)
	}

	// 存在但不受管理：用户自己写的东西，不能静默覆盖。
	foreign := filepath.Join(dir, "foreign")
	if err := os.MkdirAll(foreign, 0o755); err != nil {
		t.Fatalf("建目录失败: %v", err)
	}
	err := CheckTarget(foreign, "skillmesh:abc", false)
	assertConflict(t, err, installer.ReasonUnmanaged)

	// 加 --replace 才放行。
	if err := CheckTarget(foreign, "skillmesh:abc", true); err != nil {
		t.Fatalf("--replace 时不该报错: %v", err)
	}

	// 受管但装的是另一个 Skill：最危险的一种——会覆盖掉用户的本地修改。
	other := filepath.Join(dir, "other")
	if err := os.MkdirAll(other, 0o755); err != nil {
		t.Fatalf("建目录失败: %v", err)
	}
	if err := installer.MarkInstalled(other, "skillmesh:another-id"); err != nil {
		t.Fatalf("写标记失败: %v", err)
	}
	err = CheckTarget(other, "skillmesh:abc", false)
	assertConflict(t, err, installer.ReasonOtherSkill)

	// 同一个 Skill：放行。
	same := filepath.Join(dir, "same")
	if err := os.MkdirAll(same, 0o755); err != nil {
		t.Fatalf("建目录失败: %v", err)
	}
	if err := installer.MarkInstalled(same, "skillmesh:abc"); err != nil {
		t.Fatalf("写标记失败: %v", err)
	}
	if err := CheckTarget(same, "skillmesh:abc", false); err != nil {
		t.Fatalf("同一个 Skill 不该报冲突: %v", err)
	}

	// 目标是文件：装不了。
	file := filepath.Join(dir, "a-file")
	if err := os.WriteFile(file, []byte("x"), 0o644); err != nil {
		t.Fatalf("写文件失败: %v", err)
	}
	err = CheckTarget(file, "skillmesh:abc", false)
	assertConflict(t, err, installer.ReasonNotDir)
}

func assertConflict(t *testing.T, err error, reason string) {
	t.Helper()
	if err == nil {
		t.Fatalf("该报冲突（%s），实际没报", reason)
	}
	var conflict *installer.TargetConflictError
	if !errors.As(err, &conflict) {
		t.Fatalf("错误类型应是 TargetConflictError，实际 %T", err)
	}
	if conflict.Reason != reason {
		t.Fatalf("reason = %q，期望 %q", conflict.Reason, reason)
	}
	if ExitCode(err) != ExitConflict {
		t.Fatalf("退出码应是 %d，实际 %d", ExitConflict, ExitCode(err))
	}
}
