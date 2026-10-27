package oplog

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// 落盘的日志会被用户贴到 issue 里，家目录布局和令牌都不该跟着出去。
func TestAppendRedactsBeforeWriting(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, FileName)
	target := filepath.Join(string(filepath.Separator), "home", "someone", "skills", "demo")

	w := New(path)
	w.Record(ActionInstall, "demo", "1.0.0@"+target,
		map[string]string{"agent": "claude-code", "path": target})
	w.RecordFailure(ActionSyncFailed, "demo", "拉取失败 https://alice:secret@example.com/repo")

	entries := Tail(path, 10, "")
	if len(entries) != 2 {
		t.Fatalf("应有两条记录，实际 %d 条", len(entries))
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("读日志失败: %v", err)
	}
	content := string(raw)
	for _, leak := range []string{"someone", "alice:secret"} {
		if strings.Contains(content, leak) {
			t.Fatalf("日志里不该出现 %q，实际内容：%s", leak, content)
		}
	}
	if !strings.Contains(content, "skills") {
		t.Fatalf("末两段要保留，否则定位不到，实际内容：%s", content)
	}
}
