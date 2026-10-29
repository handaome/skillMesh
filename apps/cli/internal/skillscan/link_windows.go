//go:build windows

package skillscan

import (
	"fmt"
	"os"
	"os/exec"
	"strings"
)

// linkDir 建目录链接。
//
// Windows 上目录链接有两条路：符号链接（os.Symlink）要 SeCreateSymbolicLink 权限，
// 普通用户没开开发者模式就用不了；目录联接（junction，mklink /J）不需要任何特权，
// 但 Go 没有对应 API，只能调 mklink。
// 先试符号链接（跨盘可用），不行再退到 junction（只能同盘）。
func linkDir(target, link string) error {
	err := os.Symlink(target, link)
	if err == nil {
		return nil
	}
	output, cmdErr := exec.Command("cmd", "/c", "mklink", "/J", link, target).CombinedOutput()
	if cmdErr != nil {
		return fmt.Errorf("建目录链接失败：%w；mklink 也不成：%s",
			err, strings.TrimSpace(string(output)))
	}
	return nil
}
