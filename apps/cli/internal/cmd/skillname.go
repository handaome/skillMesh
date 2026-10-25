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

// CheckDirName 名字必须是单个路径段：不含分隔符、不含 ..、不是绝对路径。
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
	return nil
}
