package trash

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func Test卸载进回收站能找回(t *testing.T) {
	configDir := t.TempDir()
	source := filepath.Join(t.TempDir(), "demo-skill")
	if err := os.MkdirAll(source, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(source, "SKILL.md"), []byte("# demo\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	store := NewStore(configDir)
	item, err := store.Move("demo-skill", source)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(source); err == nil {
		t.Fatal("移走之后原目录不该还在")
	}
	items, err := store.Items()
	if err != nil || len(items) != 1 {
		t.Fatalf("回收站里应有 1 条，得到 %d（%v）", len(items), err)
	}

	back := filepath.Join(t.TempDir(), "demo-skill")
	if err := store.Restore(item.Entry, back); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(back, "SKILL.md"))
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "# demo\n" {
		t.Fatalf("还原的内容不对：%q", string(data))
	}
}

func Test目标已存在时不覆盖(t *testing.T) {
	configDir := t.TempDir()
	source := filepath.Join(t.TempDir(), "demo")
	if err := os.MkdirAll(source, 0o755); err != nil {
		t.Fatal(err)
	}
	store := NewStore(configDir)
	item, err := store.Move("demo", source)
	if err != nil {
		t.Fatal(err)
	}
	occupied := filepath.Join(t.TempDir(), "demo")
	if err := os.MkdirAll(occupied, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := store.Restore(item.Entry, occupied); err == nil {
		t.Fatal("目标已存在时不该覆盖")
	}
}

func Test过期条目被清理(t *testing.T) {
	store := NewStore(t.TempDir())
	old := filepath.Join(t.TempDir(), "old-skill")
	if err := os.MkdirAll(old, 0o755); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Move("old-skill", old); err != nil {
		t.Fatal(err)
	}
	// 把修改时间推到保留期之外
	entries, _ := os.ReadDir(store.dir)
	stale := time.Now().AddDate(0, 0, -60)
	for _, entry := range entries {
		_ = os.Chtimes(filepath.Join(store.dir, entry.Name()), stale, stale)
	}

	removed, err := store.Purge(30)
	if err != nil {
		t.Fatal(err)
	}
	if removed != 1 {
		t.Fatalf("应清掉 1 条，实际 %d", removed)
	}
	if items, _ := store.Items(); len(items) != 0 {
		t.Fatalf("回收站应为空，还有 %d 条", len(items))
	}
}
