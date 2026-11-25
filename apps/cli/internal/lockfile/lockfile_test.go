package lockfile

import (
	"os"
	"testing"
)

// 锁文件的两条规矩：老文件没写 version 也按 1 算；没读过的零值锁不能写出去
// ——写出去就是一份空锁文件，把用户装过的记录抹了。
func Test锁文件的版本规矩(t *testing.T) {
	root := t.TempDir()
	// 老锁文件：没有 version 字段。
	if err := os.WriteFile(Path(root), []byte("skills:\n  - name: demo\n"), 0o644); err != nil {
		t.Fatalf("写锁文件失败: %v", err)
	}
	lock, err := Load(root)
	if err != nil {
		t.Fatalf("读锁文件失败: %v", err)
	}
	if lock.Version != 1 {
		t.Fatalf("没写 version 的老文件应该按 version 1 算，实际 %d", lock.Version)
	}
	// 零值锁不能写。
	if err := (Lock{}).Save(root); err == nil {
		t.Fatalf("零值锁不该被写出去")
	}
	// 被拒绝的写入不该动过锁文件。
	again, err := Load(root)
	if err != nil {
		t.Fatalf("再读锁文件失败: %v", err)
	}
	if len(again.Skills) != 1 || again.Skills[0].Name != "demo" {
		t.Fatalf("失败的写入不该动锁文件，实际 %+v", again.Skills)
	}
}
