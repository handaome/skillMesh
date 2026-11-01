package cmd

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
)

// maxDiffLines 是行级摘要的上限。超过就只报文件级——
// 差异大到没人逐行看的时候，给个统计比给个假精确有用（设计文档 §3.3）。
const maxDiffLines = 200

// maxDiffCells 限制 LCS 的 DP 表大小，避免大文件吃光内存。
// ponytail: O(n*m) 表，超过就退化成只报文件级；真要处理超大文件再上 Myers。
const maxDiffCells = 4_000_000

// fileChange 是一个文件在新旧版本之间的变化。
type fileChange struct {
	Path      string
	Kind      string // 新增 / 修改 / 删除
	Added     int
	Removed   int
	Truncated bool // 差异过大，只报了文件级
}

// DiffTrees 比两个目录树，列出新增、修改、删除的文件。
func DiffTrees(localDir, remoteDir string) ([]fileChange, error) {
	local, err := skillscan.DirHashes(localDir)
	if err != nil {
		return nil, err
	}
	remote, err := skillscan.DirHashes(remoteDir)
	if err != nil {
		return nil, err
	}
	localIndex := ToHashMap(local)
	remoteIndex := ToHashMap(remote)

	var changes []fileChange
	for path, sum := range remoteIndex {
		previous, existed := localIndex[path]
		switch {
		case !existed:
			changes = append(changes, fileChange{Path: path, Kind: "新增"})
		case previous != sum:
			change := fileChange{Path: path, Kind: "修改"}
			change.Added, change.Removed, change.Truncated =
				lineDiff(filepath.Join(localDir, filepath.FromSlash(path)),
					filepath.Join(remoteDir, filepath.FromSlash(path)))
			changes = append(changes, change)
		}
	}
	for path := range localIndex {
		if _, still := remoteIndex[path]; !still {
			changes = append(changes, fileChange{Path: path, Kind: "删除"})
		}
	}
	sort.Slice(changes, func(i, j int) bool { return changes[i].Path < changes[j].Path })
	return changes, nil
}

// lineDiff 给出两个文本文件的增删行数。
// 不是文本、读不了、或者差异大到不值得逐行算，都只报文件级。
func lineDiff(localPath, remotePath string) (added, removed int, truncated bool) {
	localData, err := os.ReadFile(localPath)
	if err != nil {
		return 0, 0, true
	}
	remoteData, err := os.ReadFile(remotePath)
	if err != nil {
		return 0, 0, true
	}
	if skillscan.IsBinaryContent(localData) || skillscan.IsBinaryContent(remoteData) {
		return 0, 0, true
	}
	localLines := bytes.Split(localData, []byte("\n"))
	remoteLines := bytes.Split(remoteData, []byte("\n"))
	if len(localLines)*len(remoteLines) > maxDiffCells {
		return 0, 0, true
	}
	added, removed = lcsLineCounts(localLines, remoteLines)
	return added, removed, added+removed > maxDiffLines
}

// lcsLineCounts 用最长公共子序列算增删行数。
func lcsLineCounts(old, updated [][]byte) (added, removed int) {
	// common[i][j] = old[i:] 与 updated[j:] 的公共行数
	common := make([][]int, len(old)+1)
	for i := range common {
		common[i] = make([]int, len(updated)+1)
	}
	for i := len(old) - 1; i >= 0; i-- {
		for j := len(updated) - 1; j >= 0; j-- {
			if bytes.Equal(old[i], updated[j]) {
				common[i][j] = common[i+1][j+1] + 1
			} else if common[i+1][j] >= common[i][j+1] {
				common[i][j] = common[i+1][j]
			} else {
				common[i][j] = common[i][j+1]
			}
		}
	}
	i, j := 0, 0
	for i < len(old) && j < len(updated) {
		switch {
		case bytes.Equal(old[i], updated[j]):
			i++
			j++
		case common[i+1][j] >= common[i][j+1]:
			removed++
			i++
		default:
			added++
			j++
		}
	}
	removed += len(old) - i
	added += len(updated) - j
	return added, removed
}

// describeChanges 把差异压成给人看的一行行。
func describeChanges(changes []fileChange) string {
	out := ""
	for _, change := range changes {
		switch {
		case change.Truncated:
			out += fmt.Sprintf("\n    %s %s（差异较大，只报文件级）", change.Kind, change.Path)
		case change.Kind == "修改":
			out += fmt.Sprintf("\n    %s %s（+%d/-%d 行）", change.Kind, change.Path, change.Added, change.Removed)
		default:
			out += fmt.Sprintf("\n    %s %s", change.Kind, change.Path)
		}
	}
	return out
}

// diffOp 是一行编辑：空格是上下文，'-' 是旧文件里有而新文件里没有的，
// '+' 是新文件里新加的。
type diffOp struct {
	kind byte
	text string
	// oldLine / newLine 是这一行在各自文件里的行号（1 起）。
	// 新增的行没有旧行号（oldLine 为 0）。
	oldLine int
	newLine int
}

// diffLines 算两段文本的行级差异。
//
// 用的是 lcsLineCounts 那套最长公共子序列：先算出公共部分，
// 再沿着它把两边多出来的行标成删除/新增。
//
// 差异太大时不做行级比较（DP 表会吃光内存），退化成整段替换。
func diffLines(oldText, newText string) []diffOp {
	old := strings.Split(oldText, "\n")
	updated := strings.Split(newText, "\n")
	if len(old)*len(updated) > maxDiffCells {
		ops := make([]diffOp, 0, len(old)+len(updated))
		for i, line := range old {
			ops = append(ops, diffOp{kind: '-', text: line, oldLine: i + 1})
		}
		for i, line := range updated {
			ops = append(ops, diffOp{kind: '+', text: line, newLine: i + 1})
		}
		return ops
	}
	table := make([][]int, len(old)+1)
	for i := range table {
		table[i] = make([]int, len(updated)+1)
	}
	for i := len(old) - 1; i >= 0; i-- {
		for j := len(updated) - 1; j >= 0; j-- {
			if old[i] == updated[j] {
				table[i][j] = table[i+1][j+1] + 1
			} else if table[i+1][j] >= table[i][j+1] {
				table[i][j] = table[i+1][j]
			} else {
				table[i][j] = table[i][j+1]
			}
		}
	}
	var ops []diffOp
	i, j := 0, 0
	for i < len(old) && j < len(updated) {
		switch {
		case old[i] == updated[j]:
			ops = append(ops, diffOp{kind: ' ', text: old[i], oldLine: i + 1, newLine: j + 1})
			i++
			j++
		case table[i+1][j] >= table[i][j+1]:
			ops = append(ops, diffOp{kind: '-', text: old[i], oldLine: i + 1})
			i++
		default:
			ops = append(ops, diffOp{kind: '+', text: updated[j], newLine: j + 1})
			j++
		}
	}
	for ; i < len(old); i++ {
		ops = append(ops, diffOp{kind: '-', text: old[i], oldLine: i + 1})
	}
	for ; j < len(updated); j++ {
		ops = append(ops, diffOp{kind: '+', text: updated[j], newLine: j + 1})
	}
	return ops
}

// UnifiedDiff 生成两个文本之间的 unified diff。
//
// 上下文取 3 行——够看出改动发生在哪儿，又不至于把整个文件塞进补丁。
// 两边一模一样时返回空串。
func UnifiedDiff(path, oldText, newText string) string {
	ops := diffLines(oldText, newText)
	changed := false
	for _, op := range ops {
		if op.kind != ' ' {
			changed = true
			break
		}
	}
	if !changed {
		return ""
	}
	var b strings.Builder
	fmt.Fprintf(&b, "--- a/%s\n+++ b/%s\n", path, path)
	for _, hunk := range diffHunks(ops, 3) {
		b.WriteString(hunk)
	}
	return b.String()
}

// diffHunks 把编辑序列切成 unified diff 的各个 hunk。
func diffHunks(ops []diffOp, context int) []string {
	changed := []int{}
	for i, op := range ops {
		if op.kind != ' ' {
			changed = append(changed, i)
		}
	}
	if len(changed) == 0 {
		return nil
	}
	// 相邻的改动并成一个 hunk，前后各带 context 行上下文。
	type span struct{ from, to int }
	spans := []span{}
	for _, index := range changed {
		from := index - context
		if from < 0 {
			from = 0
		}
		to := index + context
		if to > len(ops)-1 {
			to = len(ops) - 1
		}
		if len(spans) > 0 && from <= spans[len(spans)-1].to+1 {
			if to > spans[len(spans)-1].to {
				spans[len(spans)-1].to = to
			}
			continue
		}
		spans = append(spans, span{from: from, to: to})
	}
	hunks := make([]string, 0, len(spans))
	for _, s := range spans {
		oldStart, oldCount, newStart, newCount := 0, 0, 0, 0
		var body strings.Builder
		for _, op := range ops[s.from : s.to+1] {
			switch op.kind {
			case ' ':
				if oldCount == 0 {
					oldStart = op.oldLine
				}
				if newCount == 0 {
					newStart = op.newLine
				}
				oldCount++
				newCount++
				body.WriteString(" " + op.text + "\n")
			case '-':
				if oldCount == 0 {
					oldStart = op.oldLine
				}
				oldCount++
				body.WriteString("-" + op.text + "\n")
			case '+':
				if newCount == 0 {
					newStart = op.newLine
				}
				newCount++
				body.WriteString("+" + op.text + "\n")
			}
		}
		hunks = append(hunks, fmt.Sprintf("@@ -%d,%d +%d,%d @@\n%s",
			oldStart, oldCount, newStart, newCount, body.String()))
	}
	return hunks
}
