package cmd

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"sort"

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
	localIndex := index(local)
	remoteIndex := index(remote)

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

func index(files []skillscan.FileDigest) map[string]string {
	out := make(map[string]string, len(files))
	for _, file := range files {
		out[file.Path] = file.SHA256
	}
	return out
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
	if isBinaryContent(localData) || isBinaryContent(remoteData) {
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

func isBinaryContent(data []byte) bool {
	head := data
	if len(head) > 8192 {
		head = head[:8192]
	}
	return bytes.IndexByte(head, 0) >= 0
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
