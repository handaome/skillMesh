package cmd

import (
	"github.com/handaome/skillmesh/apps/cli/internal/installer"
)

// CheckTarget 检查安装目标能不能用。
//
// 挡四种情况：目标是符号链接（装上去会把链接替换掉，而链接指向的可能是
// 用户自己的目录）、目标是文件（不是目录）、目标存在但不受管理（可能是用户
// 手写的 Skill）、目标装的是另一个 Skill。
//
// source 是「这次要装的东西的标识」：注册中心装的是 skillmesh:<skill-id>，
// Git 装的是仓库地址。标识相同说明是同一个东西，可以覆盖。
func CheckTarget(target, source string, replace bool) error {
	state, err := installer.ClassifyTarget(target)
	if err != nil {
		return err
	}
	switch state {
	case installer.TargetAbsent:
		return nil
	case installer.TargetSymlink:
		return &installer.TargetConflictError{TargetPath: target, Reason: installer.ReasonSymlink}
	case installer.TargetFile:
		return &installer.TargetConflictError{TargetPath: target, Reason: installer.ReasonNotDir}
	}
	marker := installer.MarkerOf(target)
	if marker == "" {
		if replace {
			return nil
		}
		return &installer.TargetConflictError{TargetPath: target, Reason: installer.ReasonUnmanaged}
	}
	if marker == source || replace {
		return nil
	}
	return &installer.TargetConflictError{TargetPath: target, Reason: installer.ReasonOtherSkill}
}
