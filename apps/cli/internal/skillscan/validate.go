// Package skillscan 发现与校验 Skill 目录。安装前的安全闸门在这里。
package skillscan

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

type Limits struct {
	MaxFileBytes  int64
	MaxTotalBytes int64
	MaxFiles      int
}

// DefaultLimits 对应详细设计 §3.1.2 的 max_file_size_mb / max_total_size_mb。
var DefaultLimits = Limits{
	MaxFileBytes:  20 << 20,
	MaxTotalBytes: 50 << 20,
	MaxFiles:      5000,
}

// ValidateDir 校验一个 Skill 目录：必须有 SKILL.md，不得有逃出根目录的符号链接，
// 单文件/总量/文件数不得超限。不跟随指向目录外的符号链接（详细设计 §6.1）。
func ValidateDir(dir string, limits Limits) error {
	info, err := os.Stat(dir)
	if err != nil || !info.IsDir() {
		return fmt.Errorf("Skill 目录不存在: %s", dir)
	}
	if _, err := os.Stat(filepath.Join(dir, SkillFile)); err != nil {
		return fmt.Errorf("缺少 SKILL.md，不是合法的 Skill 目录: %s", dir)
	}

	root, err := filepath.EvalSymlinks(dir)
	if err != nil {
		return fmt.Errorf("解析目录失败: %w", err)
	}

	var files int
	var total int64
	walkErr := filepath.Walk(root, func(path string, fi os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if fi.Mode()&os.ModeSymlink != 0 {
			target, err := filepath.EvalSymlinks(path)
			if err != nil {
				return fmt.Errorf("符号链接无法解析: %s", path)
			}
			if !Within(root, target) {
				return fmt.Errorf("符号链接指向目录外，拒绝: %s", target)
			}
			return nil
		}
		if fi.IsDir() {
			if path != root && IsSkippedDir(fi.Name()) {
				return filepath.SkipDir
			}
			return nil
		}
		files++
		if files > limits.MaxFiles {
			return fmt.Errorf("文件数超过上限 %d", limits.MaxFiles)
		}
		if fi.Size() > limits.MaxFileBytes {
			return fmt.Errorf("文件 %s 超过单文件大小上限 %d 字节", path, limits.MaxFileBytes)
		}
		total += fi.Size()
		if total > limits.MaxTotalBytes {
			return fmt.Errorf("目录总大小超过上限 %d 字节", limits.MaxTotalBytes)
		}
		return nil
	})
	return walkErr
}

// Within 判断 target 是否在 root 之内（含 root 本身）。
func Within(root, target string) bool {
	rel, err := filepath.Rel(root, target)
	if err != nil {
		return false
	}
	return rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}
