package installer

import (
	"archive/zip"
	"bytes"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// 解包限额。与服务端 ZipManifest 的口径对齐（详细设计 §3.5、§7.3）。
const (
	// MaxEntries 是单个包允许的条目数上限。
	MaxEntries = 10000
	// MaxUnpackedBytes 是解压后总量上限——归档炸弹靠的就是「压得小、胀得狠」。
	MaxUnpackedBytes = 200 << 20
)

// ExtractZip 解包到 destRoot 并返回含 SKILL.md 的顶层目录。
// 拒绝路径穿越与绝对路径条目（详细设计 §3.5 安装事务第 3 步）。
func ExtractZip(data []byte, destRoot string) (string, error) {
	reader, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return "", fmt.Errorf("制品不是合法 zip: %w", err)
	}
	if len(reader.File) > MaxEntries {
		return "", fmt.Errorf("制品条目数超过上限 %d（实际 %d）", MaxEntries, len(reader.File))
	}
	for _, entry := range reader.File {
		if err := checkEntryName(entry.Name); err != nil {
			return "", err
		}
	}
	var unpacked int64
	for _, entry := range reader.File {
		out := filepath.Join(destRoot, filepath.FromSlash(entry.Name))
		if !withinRoot(destRoot, out) {
			return "", fmt.Errorf("制品包含路径穿越条目: %s", entry.Name)
		}
		if entry.FileInfo().IsDir() {
			if err := os.MkdirAll(out, 0o755); err != nil {
				return "", err
			}
			continue
		}
		// 边解边计：解压后的总量超了就停——炸弹已经落盘的部分会被一起清掉。
		unpacked += int64(entry.UncompressedSize64)
		if unpacked > MaxUnpackedBytes {
			return "", fmt.Errorf("制品解压后超过上限 %d 字节（归档炸弹？）", MaxUnpackedBytes)
		}
		if err := extractFile(entry, out); err != nil {
			return "", err
		}
	}
	return findSkillRoot(destRoot)
}

func checkEntryName(name string) error {
	if strings.HasPrefix(name, "/") || strings.HasPrefix(name, `\`) {
		return fmt.Errorf("制品包含绝对路径条目: %s", name)
	}
	if strings.Contains(name, "..") {
		return fmt.Errorf("制品包含路径穿越条目: %s", name)
	}
	return nil
}

func extractFile(entry *zip.File, out string) error {
	if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
		return err
	}
	rc, err := entry.Open()
	if err != nil {
		return err
	}
	defer rc.Close()

	mode := entry.Mode().Perm()
	if mode == 0 {
		mode = 0o644
	}
	w, err := os.OpenFile(out, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, mode)
	if err != nil {
		return err
	}
	if _, err := io.Copy(w, rc); err != nil {
		w.Close()
		return err
	}
	return w.Close()
}

func withinRoot(root, target string) bool {
	rel, err := filepath.Rel(root, target)
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}

// findSkillRoot 兼容两种打包布局：顶层直接是 SKILL.md，或包一层目录名。
func findSkillRoot(root string) (string, error) {
	if _, err := os.Stat(filepath.Join(root, "SKILL.md")); err == nil {
		return root, nil
	}
	entries, err := os.ReadDir(root)
	if err != nil {
		return "", err
	}
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		candidate := filepath.Join(root, entry.Name())
		if _, err := os.Stat(filepath.Join(candidate, "SKILL.md")); err == nil {
			return candidate, nil
		}
	}
	return "", fmt.Errorf("制品中找不到 SKILL.md")
}
