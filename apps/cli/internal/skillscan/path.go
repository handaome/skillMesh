package skillscan

import (
	"os"
	"path/filepath"
)

// maxWalkDepth 是遍历的深度上限。链接成环时靠它兜底收场。
const maxWalkDepth = 32

// LinkTarget 取路径指向的真实目标；不是链接就返回 false。
//
// 不依赖 filepath.EvalSymlinks：Windows 上目录链接（junction / 重解析点）
// 在 Go 里是 ModeIrregular，EvalSymlinks 认为它不是链接，原样返回；
// 而 os.Readlink 能拿到目标。
func LinkTarget(path string) (string, bool) {
	if _, err := os.Lstat(path); err != nil {
		return "", false
	}
	target, err := os.Readlink(path)
	if err != nil || target == "" {
		return "", false
	}
	if !filepath.IsAbs(target) {
		target = filepath.Join(filepath.Dir(path), target)
	}
	return target, true
}

// WriteTarget 返回内容该写到哪：链接就写它指向的真实目录。
//
// 链接是用户（或 skills 管理器）建的结构，替换成实体目录等于把别人的布局拆了；
// 内容更新应该落在链接指向的地方，链接本身留着。
func WriteTarget(path string) string {
	if target, ok := LinkTarget(path); ok {
		return target
	}
	return path
}

// WalkFollow 遍历目录，判断类型时用 os.Stat（跟随链接）而不是 Lstat。
//
// 技能目录常常是链接（skills 管理器把库里的目录挂进 Agent 目录）。
// Windows 上这类链接被 Lstat 报成「既不是目录也不是符号链接」的重解析点，
// filepath.Walk 第一步就进不去——整份内容被当成空目录，摘要算出来是空哈希，
// 同步就会把空包当成新版本传上去。
//
// 子目录用过 os.Stat 取到的 FileInfo 再决定要不要进去，所以链接目录能正常展开。
// 回调返回 filepath.SkipDir 时跳过该目录，与 filepath.Walk 一致。
func WalkFollow(root string, fn func(path string, info os.FileInfo, err error) error) error {
	return walkFollow(root, 0, fn)
}

func walkFollow(path string, depth int, fn func(string, os.FileInfo, error) error) error {
	info, err := os.Stat(path)
	if err != nil {
		return fn(path, nil, err)
	}
	if err := fn(path, info, nil); err != nil {
		if err == filepath.SkipDir {
			return nil
		}
		return err
	}
	if !info.IsDir() {
		return nil
	}
	if depth >= maxWalkDepth {
		// 太深了多半是链接环，停下来比爆栈好。
		return nil
	}
	entries, err := os.ReadDir(path)
	if err != nil {
		return fn(path, info, err)
	}
	for _, entry := range entries {
		if err := walkFollow(filepath.Join(path, entry.Name()), depth+1, fn); err != nil {
			return err
		}
	}
	return nil
}
