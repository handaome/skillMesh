package cmd

import (
	"fmt"
	"path/filepath"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
)

// skillDirName 取出包声明的 Skill 名字，并确认它能安全地当目录名用。
//
// 声明名写在包里的 SKILL.md 上，是发布方给的：可以是 ../..，也可以是绝对路径。
// 它会拼进 Agent 目录的路径里，不校验就等于让发布方决定往哪写。
func skillDirName(dir string) (string, error) {
	name := skillscan.SkillName(dir)
	if err := CheckDirName(name); err != nil {
		return "", err
	}
	return name, nil
}

// CheckDirName 名字必须是单个路径段，且在 Windows 上能当目录名用。
//
// 拒绝而不是替换：替换会让安装目录名与声明名不一致，
// 破坏 update 与锁文件的按名匹配。
func CheckDirName(name string) error {
	if name == "" {
		return fmt.Errorf("Skill 名字为空")
	}
	if name == "." || name == ".." {
		return fmt.Errorf("Skill 名字 %q 不是合法的目录名", name)
	}
	if strings.ContainsAny(name, `/\`) {
		return fmt.Errorf("Skill 名字 %q 含路径分隔符，不能当目录名用", name)
	}
	if strings.Contains(name, ":") {
		// Windows 的盘符与备用数据流都卡在冒号上。
		return fmt.Errorf("Skill 名字 %q 含非法字符 ':'", name)
	}
	if filepath.IsAbs(name) || filepath.VolumeName(name) != "" {
		return fmt.Errorf("Skill 名字 %q 是绝对路径，不能当目录名用", name)
	}
	if i := strings.IndexAny(name, windowsReservedChars); i >= 0 {
		return fmt.Errorf("Skill 名字 %q 含 Windows 保留字符 %q", name, name[i:i+1])
	}
	if hasControlChar(name) {
		return fmt.Errorf("Skill 名字 %q 含控制字符", name)
	}
	// 尾随的点与空格：Win32 会把它们裁掉，于是 foo. 装出来是 foo，
	// 和已有的 foo 撞成同一个目录——受管就直接覆盖掉了。
	if strings.TrimRight(name, " .") != name {
		return fmt.Errorf("Skill 名字 %q 结尾有点或空格，Windows 上会被裁掉", name)
	}
	if device := windowsDeviceName(name); device != "" {
		return fmt.Errorf("Skill 名字 %q 是 Windows 的保留设备名", name)
	}
	return nil
}

// windowsReservedChars 是 Windows 文件名里的保留字符。
//
// 含它们的技能装到 Windows 上会直接失败，报的还是原始的操作系统错误，
// 看不出是名字的问题。
const windowsReservedChars = `<>"|?*`

// windowsDeviceNames 是 Windows 的保留设备名。
//
// 建目录时这些名字指向设备而不是文件。
var windowsDeviceNames = []string{
	"CON", "PRN", "AUX", "NUL",
	"COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
	"LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
}

// windowsDeviceName 是不是 Windows 的保留设备名。
//
// 带扩展名也算：COM1.txt 在 Win32 上照样指向串口设备。
func windowsDeviceName(name string) string {
	base := name
	if i := strings.IndexByte(base, '.'); i >= 0 {
		base = base[:i]
	}
	upper := strings.ToUpper(base)
	for _, device := range windowsDeviceNames {
		if upper == device {
			return device
		}
	}
	return ""
}

// hasControlChar 有没有控制字符。
func hasControlChar(name string) bool {
	for _, r := range name {
		if r < 0x20 || r == 0x7f {
			return true
		}
	}
	return false
}
