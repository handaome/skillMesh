//go:build !windows

package skillscan

import "os"

// linkDir 建目录链接。Unix 上就是符号链接。
func linkDir(target, link string) error {
	return os.Symlink(target, link)
}
