package cmd

import (
	"fmt"
	"os"
	"path/filepath"
)

// projectRootOverride 是 --project 指定的根目录。
var projectRootOverride string

// projectRootDir 返回本次操作的项目根目录。
//
// 优先级：--project 指定的目录 > 从当前目录向上找到的项目根 > 当前目录。
//
// 找项目根看的是 .git 或 skillmesh.yaml：在仓库的子目录里跑命令时，
// 锁文件和项目级安装目录该跟着仓库走，而不是跟着当前子目录走。
func projectRootDir() string {
	if projectRootOverride != "" {
		return projectRootOverride
	}
	dir := mustGetwd()
	if dir == "" {
		return ""
	}
	return findProjectRoot(dir)
}

// findProjectRoot 从 dir 向上找项目根目录；找不到就还是 dir。
func findProjectRoot(dir string) string {
	for current := dir; current != ""; {
		if isProjectRoot(current) {
			return current
		}
		parent := filepath.Dir(current)
		if parent == current {
			break
		}
		current = parent
	}
	return dir
}

// isProjectRoot 这个目录像不像项目根。
func isProjectRoot(dir string) bool {
	for _, name := range []string{".git", "skillmesh.yaml"} {
		if _, err := os.Stat(filepath.Join(dir, name)); err == nil {
			return true
		}
	}
	return false
}

// setProjectRoot 记下 --project 指定的根目录。
func setProjectRoot(path string) error {
	abs, err := filepath.Abs(path)
	if err != nil {
		return fmt.Errorf("--project 指定的路径用不了: %w", err)
	}
	info, err := os.Stat(abs)
	if err != nil {
		return fmt.Errorf("--project 指定的路径不存在: %s", abs)
	}
	if !info.IsDir() {
		return fmt.Errorf("--project 指定的不是目录: %s", abs)
	}
	projectRootOverride = abs
	return nil
}
