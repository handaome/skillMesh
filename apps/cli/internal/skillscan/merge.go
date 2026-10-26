// Package skillscan 的三方文本合并。
//
// 设计文档 §6.3：同一文件双方变更时「尝试文本三方合并；无法安全合并则生成冲突副本」。
// 只做行级合并——够用、可预测；语法级合并要按语言解析，不值当。
package skillscan

import (
	"bytes"
	"strings"
)

// Merge3 做行级三方合并：base 是上次同步时的内容，local 与 cloud 是两边的当前内容。
// 第二个返回值 false 表示有无法自动合并的冲突，调用方应生成冲突副本而不是覆盖。
func Merge3(base, local, cloud []byte) ([]byte, bool) {
	baseLines := splitLines(base)
	n := len(baseLines)
	localLines := splitLines(local)
	cloudLines := splitLines(cloud)
	localEdits := editsFromBase(baseLines, localLines)
	cloudEdits := editsFromBase(baseLines, cloudLines)

	var out []string
	conflict := false
	pos := 0 // 已经处理到的基线行
	li := 0  // 本地编辑列表的游标
	ci := 0  // 云端编辑列表的游标

	for {
		localEdit, cloudEdit := editAt(localEdits, li, pos), editAt(cloudEdits, ci, pos)
		if localEdit == nil && cloudEdit == nil {
			out = append(out, baseLines[pos:]...)
			break
		}
		// 基线里到下一个改动点之间的行原样保留
		next := len(baseLines)
		if localEdit != nil {
			next = min(next, localEdit.baseStart)
		}
		if cloudEdit != nil {
			next = min(next, cloudEdit.baseStart)
		}
		out = append(out, baseLines[pos:next]...)
		if next >= n && !(hasInsertion(localEdit) || hasInsertion(cloudEdit)) {
			break
		}

		end := next
		var localPart, cloudPart []string
		hasLocal, hasCloud := false, false
		if localEdit != nil && localEdit.baseStart == next {
			localPart = localLines[localEdit.replacementStart:localEdit.replacementEnd]
			end = max(end, localEdit.baseEnd)
			hasLocal = true
		}
		if cloudEdit != nil && cloudEdit.baseStart == next {
			cloudPart = cloudLines[cloudEdit.replacementStart:cloudEdit.replacementEnd]
			end = max(end, cloudEdit.baseEnd)
			hasCloud = true
		}

		switch {
		case hasLocal && !hasCloud:
			out = append(out, localPart...)
		case hasCloud && !hasLocal:
			out = append(out, cloudPart...)
		case sameLines(localPart, cloudPart):
			out = append(out, localPart...)
		default:
			// 合不了就把两边都留在线上，人来定夺——覆盖掉任何一边都是丢数据。
			conflict = true
			out = append(out, "<<<<<<< 本地")
			out = append(out, localPart...)
			out = append(out, "=======")
			out = append(out, cloudPart...)
			out = append(out, ">>>>>>> 云端")
		}

		// 消费掉落在这一段里的编辑，纯插入（baseStart == baseEnd）也要消费，
		// 否则游标不动会死循环。
		for li < len(localEdits) && localEdits[li].baseStart <= next {
			li++
		}
		for ci < len(cloudEdits) && cloudEdits[ci].baseStart <= next {
			ci++
		}
		pos = end
	}
	return []byte(strings.Join(out, "\n")), !conflict
}

// hasInsertion 这段编辑是纯插入（不消耗基线行）。纯插入不会让 pos 前进，
// 必须靠它避免死循环。
func hasInsertion(e *edit) bool {
	return e != nil && e.baseStart == e.baseEnd
}

// editAt 取从 basePos 起的第一段编辑；没有就返回 nil。
func editAt(edits []edit, cursor, basePos int) *edit {
	if cursor >= len(edits) || edits[cursor].baseStart < basePos {
		return nil
	}
	return &edits[cursor]
}

// edit 是相对基线的一段替换：[baseStart, baseEnd) 被
// [replacementStart, replacementEnd) 取代。纯插入时 baseStart == baseEnd。
type edit struct {
	baseStart, baseEnd               int
	replacementStart, replacementEnd int
}

// editsFromBase 用最长公共子序列把 base 变成 target，抽出编辑列表。
func editsFromBase(base, target []string) []edit {
	n, m := len(base), len(target)
	// lcs[i][j] = base[i:] 与 target[j:] 的最长公共子序列长度
	lcs := make([][]int, n+1)
	for i := range lcs {
		lcs[i] = make([]int, m+1)
	}
	for i := n - 1; i >= 0; i-- {
		for j := m - 1; j >= 0; j-- {
			switch {
			case base[i] == target[j]:
				lcs[i][j] = lcs[i+1][j+1] + 1
			case lcs[i+1][j] >= lcs[i][j+1]:
				lcs[i][j] = lcs[i+1][j]
			default:
				lcs[i][j] = lcs[i][j+1]
			}
		}
	}

	var edits []edit
	i, j := 0, 0
	pendingStart, replacementStart := -1, -1
	for i < n && j < m {
		if base[i] == target[j] {
			if pendingStart >= 0 {
				edits = append(edits, edit{pendingStart, i, replacementStart, j})
				pendingStart = -1
			}
			i++
			j++
			continue
		}
		if pendingStart < 0 {
			pendingStart, replacementStart = i, j
		}
		if lcs[i+1][j] >= lcs[i][j+1] {
			i++
		} else {
			j++
		}
	}
	if pendingStart >= 0 {
		edits = append(edits, edit{pendingStart, n, replacementStart, m})
	} else if i < n || j < m {
		// 尾部删除或插入
		edits = append(edits, edit{i, n, j, m})
	}
	return edits
}

func splitLines(text []byte) []string {
	if len(text) == 0 {
		return nil
	}
	normalized := bytes.ReplaceAll(text, []byte("\r\n"), []byte("\n"))
	lines := strings.Split(string(normalized), "\n")
	if len(lines) > 0 && lines[len(lines)-1] == "" {
		lines = lines[:len(lines)-1]
	}
	return lines
}

func sameLines(left, right []string) bool {
	if len(left) != len(right) {
		return false
	}
	for i := range left {
		if left[i] != right[i] {
			return false
		}
	}
	return true
}
