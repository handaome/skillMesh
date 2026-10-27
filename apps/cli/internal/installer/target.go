package installer

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// TargetState 是安装目标当前的样子。
type TargetState int

const (
	// TargetAbsent：目标不存在，可以直接装。
	TargetAbsent TargetState = iota
	// TargetDir：目标是真实目录。
	TargetDir
	// TargetFile：目标是真实文件，不是目录。
	TargetFile
	// TargetSymlink：目标是符号链接。
	TargetSymlink
)

// 冲突原因。脚本靠这几个值判断该换目录还是该让用户确认。
const (
	// ReasonUnmanaged：目标存在，但不是 SkillMesh 装的。
	ReasonUnmanaged = "unmanaged"
	// ReasonSymlink：目标是符号链接。
	ReasonSymlink = "symlink"
	// ReasonNotDir：目标存在但不是目录。
	ReasonNotDir = "not_dir"
	// ReasonOtherSkill：目标装的是另一个 Skill。
	ReasonOtherSkill = "managed_by_other"
)

// targetConflictText 是冲突原因的人话说法。
var targetConflictText = map[string]string{
	ReasonUnmanaged:  "已存在且不受 SkillMesh 管理",
	ReasonSymlink:    "是符号链接，不能直接替换",
	ReasonNotDir:     "已存在但不是目录",
	ReasonOtherSkill: "已装的是另一个 Skill",
}

// TargetConflictError 是「安装目标被占」的错。
//
// 带上原因而不是只给一句话：脚本要能自己判断该换目录还是该让用户确认，
// 不能去匹配中文文案——改一次文案就断一次。
type TargetConflictError struct {
	TargetPath string
	Reason     string
}

func (e *TargetConflictError) Error() string {
	text, ok := targetConflictText[e.Reason]
	if !ok {
		text = e.Reason
	}
	return fmt.Sprintf("目标已被占用: %s（%s）", e.TargetPath, text)
}

// ClassifyTarget 判断目标路径是什么形态。
//
// 用 Lstat 而不是 Stat：Stat 跟着符号链接走，看到的是链接指向的东西，
// 而这里要判断的恰恰是「这个路径本身是不是链接」。
func ClassifyTarget(path string) (TargetState, error) {
	info, err := os.Lstat(path)
	if err != nil {
		if os.IsNotExist(err) {
			return TargetAbsent, nil
		}
		return TargetAbsent, err
	}
	switch {
	case info.Mode()&os.ModeSymlink != 0:
		return TargetSymlink, nil
	case info.IsDir():
		return TargetDir, nil
	default:
		return TargetFile, nil
	}
}

// MarkerOf 读安装标记里的原始内容；不是本应用装的返回空串。
func MarkerOf(dir string) string {
	data, err := os.ReadFile(filepath.Join(dir, markerFile))
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(data))
}

// SkillIDOf 从安装标记里取出 Skill ID；不是注册中心装的返回空串。
func SkillIDOf(dir string) string {
	raw := MarkerOf(dir)
	if !strings.HasPrefix(raw, "skillmesh:") {
		return ""
	}
	return strings.TrimPrefix(raw, "skillmesh:")
}
