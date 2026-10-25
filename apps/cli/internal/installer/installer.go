// Package installer 把 Skill 装进 Agent 目录。要求「临时目录 → 校验 → 原子切换」，
// 失败时不破坏当前可用版本（详细设计 附录 A.4）。
package installer

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
)

const (
	StagingPrefix = ".skillmesh-staging-"
	BackupSuffix  = ".skillmesh-backup"
	markerFile    = ".skillmesh-install"
)

// InstallAtomic 先复制到同级暂存目录，校验通过后原子切换到最终路径。
func InstallAtomic(src, target string, requireSkillMd bool) error {
	if requireSkillMd {
		if _, err := os.Stat(filepath.Join(src, "SKILL.md")); err != nil {
			return fmt.Errorf("源目录缺少 SKILL.md")
		}
	}
	parent := filepath.Dir(target)
	if err := os.MkdirAll(parent, 0o755); err != nil {
		return err
	}

	staging, err := os.MkdirTemp(parent, StagingPrefix)
	if err != nil {
		return err
	}
	defer os.RemoveAll(staging)

	stagedTarget := filepath.Join(staging, filepath.Base(target))
	if err := CopyTree(src, stagedTarget); err != nil {
		return fmt.Errorf("复制到暂存目录失败: %w", err)
	}

	backup := ""
	if _, err := os.Stat(target); err == nil {
		backup = target + BackupSuffix
		os.RemoveAll(backup)
		if err := os.Rename(target, backup); err != nil {
			return fmt.Errorf("备份现有安装失败: %w", err)
		}
	}
	if err := os.Rename(stagedTarget, target); err != nil {
		if backup != "" {
			// 切换失败就把旧版本放回去，保证用户手上始终有一份可用安装
			_ = os.Rename(backup, target)
		}
		return fmt.Errorf("切换安装目录失败: %w", err)
	}
	if backup != "" {
		os.RemoveAll(backup)
	}
	return nil
}

func CopyTree(src, dst string) error {
	return filepath.Walk(src, func(path string, fi os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(src, path)
		if err != nil {
			return err
		}
		out := filepath.Join(dst, rel)
		if fi.IsDir() {
			return os.MkdirAll(out, 0o755)
		}
		if !fi.Mode().IsRegular() {
			return nil // 跳过符号链接等非普通文件
		}
		return copyFile(path, out, fi.Mode())
	})
}

func copyFile(src, dst string, mode os.FileMode) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, mode.Perm())
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}

// IsManaged 判断目录是否由 SkillMesh 安装。
func IsManaged(dir string) bool {
	_, err := os.Stat(filepath.Join(dir, markerFile))
	return err == nil
}

func MarkInstalled(dir, source string) error {
	return os.WriteFile(filepath.Join(dir, markerFile), []byte(source+"\n"), 0o644)
}

// RemoveUnmanaged 拒绝删除不受管理的目录，避免误删用户手写的 Skill。
func RemoveUnmanaged(dir string) error {
	if _, err := os.Stat(dir); os.IsNotExist(err) {
		return nil
	}
	if !IsManaged(dir) {
		return fmt.Errorf("%s 不由 SkillMesh 管理，拒绝删除（用 --replace 可覆盖）", dir)
	}
	return os.RemoveAll(dir)
}
