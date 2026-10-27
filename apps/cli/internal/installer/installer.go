// Package installer 把 Skill 装进 Agent 目录。要求「临时目录 → 校验 → 原子切换」，
// 失败时不破坏当前可用版本（详细设计 附录 A.4）。
package installer

import (
	"fmt"
	"io"
	"os"
	"path/filepath"

	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
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

	// 目标是符号链接就拒绝：os.Rename 挪走的是链接本身，而链接指向的
	// 可能是用户自己的目录——替换掉等于动了用户没让动的文件。
	state, err := ClassifyTarget(target)
	if err != nil {
		return err
	}
	if state == TargetSymlink {
		return &TargetConflictError{TargetPath: target, Reason: ReasonSymlink}
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

// CopyTree 复制目录内容，跳过隐藏目录、噪音目录与隐藏文件。
//
// 跳过规则与打包、算摘要、校验目录是同一套（skillscan.IsSkippedDir /
// IsHiddenFile）：装进去的东西要是和发出去的、和服务端校验的不是同一批，
// 审核页看到的文件列表就和本机对不上；.cache、.venv 这类目录进了 Agent
// 目录也只会白占地方。
func CopyTree(src, dst string) error {
	return skillscan.WalkFollow(src, func(path string, fi os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(src, path)
		if err != nil {
			return err
		}
		out := filepath.Join(dst, rel)
		if fi.IsDir() {
			if path != src && skillscan.IsSkippedDir(fi.Name()) {
				return filepath.SkipDir
			}
			return os.MkdirAll(out, 0o755)
		}
		if !fi.Mode().IsRegular() {
			return nil // 跳过符号链接等非普通文件
		}
		if skillscan.IsHiddenFile(fi.Name()) {
			return nil // 隐藏文件是作者的本地状态，不进安装
		}
		return CopyFile(path, out, fi.Mode())
	})
}

// CopyFile 复制单个文件，落盘权限取 mode 的权限位。
//
// 已存在的目标文件不改权限（O_CREATE 只在创建时用 perm）——要覆盖权限
// 就先删目标，或复制后自己 chmod。
func CopyFile(src, dst string, mode os.FileMode) error {
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
