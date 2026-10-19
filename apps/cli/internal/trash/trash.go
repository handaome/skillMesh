// Package trash 保留被卸载的 Skill，让删除可恢复
// （设计文档 §6.4 软删除/回收期、§15.1「删除操作支持恢复」）。
//
// 放在配置目录旁边而不是 Agent 目录里：回收站不该被 Agent 扫到，
// 更不该在某次 sync 时被当成一个新 Skill 上传。
package trash

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"time"
)

// RetentionDays 是回收站默认保留天数。
const RetentionDays = 30

// Item 是一条被回收的记录。
type Item struct {
	Name    string    `json:"name"`
	Entry   string    `json:"entry"` // 回收站里的目录名
	From    string    `json:"from"`  // 原来装在哪
	At      time.Time `json:"at"`
	SizeKiB int64     `json:"size_kib"`
}

// Store 是本机的回收站。
type Store struct{ dir string }

func NewStore(configDir string) *Store {
	return &Store{dir: filepath.Join(configDir, "trash")}
}

// Move 把目录移进回收站，返回那条记录。
func (s *Store) Move(name, from string) (Item, error) {
	if err := os.MkdirAll(s.dir, 0o755); err != nil {
		return Item{}, err
	}
	at := time.Now()
	entry := fmt.Sprintf("%s-%s", name, at.Format("20060102-150405"))
	target := filepath.Join(s.dir, entry)
	if err := os.Rename(from, target); err != nil {
		return Item{}, err
	}
	size, _ := dirSize(target)
	return Item{Name: name, Entry: entry, From: from, At: at, SizeKiB: size / 1024}, nil
}

// Items 列出回收站里的东西，最新的在前。
func (s *Store) Items() ([]Item, error) {
	entries, err := os.ReadDir(s.dir)
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var items []Item
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		at := info.ModTime()
		size, _ := dirSize(filepath.Join(s.dir, entry.Name()))
		items = append(items, Item{
			Name:    entry.Name(),
			Entry:   entry.Name(),
			From:    "",
			At:      at,
			SizeKiB: size / 1024,
		})
	}
	sort.Slice(items, func(i, j int) bool { return items[i].At.After(items[j].At) })
	return items, nil
}

// Restore 把回收站里的一条放回目标路径。
func (s *Store) Restore(entry, to string) error {
	from := filepath.Join(s.dir, entry)
	if _, err := os.Stat(from); err != nil {
		return fmt.Errorf("回收站里没有 %s", entry)
	}
	if _, err := os.Stat(to); err == nil {
		return fmt.Errorf("目标已存在，先处理掉它：%s", to)
	}
	if err := os.MkdirAll(filepath.Dir(to), 0o755); err != nil {
		return err
	}
	return os.Rename(from, to)
}

// Purge 删掉超过保留期的记录，返回删了几条。
func (s *Store) Purge(retentionDays int) (int, error) {
	items, err := s.Items()
	if err != nil {
		return 0, err
	}
	cutoff := time.Now().AddDate(0, 0, -retentionDays)
	removed := 0
	for _, item := range items {
		if item.At.Before(cutoff) {
			if err := os.RemoveAll(filepath.Join(s.dir, item.Entry)); err != nil {
				return removed, err
			}
			removed++
		}
	}
	return removed, nil
}

func dirSize(path string) (int64, error) {
	var total int64
	err := filepath.Walk(path, func(_ string, info os.FileInfo, err error) error {
		if err != nil {
			return nil // 读不到的就当没有，不让一次权限问题毁掉整次统计
		}
		if !info.IsDir() {
			total += info.Size()
		}
		return nil
	})
	return total, err
}
