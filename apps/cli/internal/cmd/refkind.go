package cmd

import "github.com/handaome/skillmesh/apps/cli/internal/gitx"

// validRefTypes 是 --ref-type 允许的取值（设计文档 §7.1）。
var validRefTypes = []string{gitx.RefBranch, gitx.RefTag, gitx.RefCommit, gitx.RefDefault}

// pinnedRefType 这个来源是不是钉死的。
//
// 固定到 commit 或 tag 的来源不会跟着远端漂：版本只由用户明确升级才变
// （设计文档 §3.3.5、详细设计 §3.7）。
// commit 顺带躲开一个坑：ls-remote 按 ref 名匹配，commit sha 匹配不到。
func pinnedRefType(refType string) bool {
	return refType == gitx.RefCommit || refType == gitx.RefTag
}
