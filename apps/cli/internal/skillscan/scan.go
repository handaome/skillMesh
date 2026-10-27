package skillscan

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// maxCandidateDepth 限制扫描深度，避免在大仓库里走太久。
const maxCandidateDepth = 4

// Candidate 是仓库里一个可能的 Skill 目录。
type Candidate struct {
	Dir  string
	Name string
}

// Candidates 找出根目录下所有含 SKILL.md 的子目录。
//
// recursive 为 false 时只看直接子目录：有些 Agent 的 skills 目录是扁平的，
// 递归下去会把技能自己带的子目录也当成候选。
//
// 符号链接指向目录时也认：不少人用链接把技能目录挂进 Agent 目录
// （skills 管理器就是这么干的），filepath.Walk 按 Lstat 判定，会把链接当成
// 普通文件跳过——结果是整批技能在 scan / sync 眼里「不存在」。
// 链接本身当候选，不往里递归：链接指向的是技能本体，再挖下去就是别人的仓库了。
func Candidates(root string, recursive bool) ([]Candidate, error) {
	var found []Candidate
	err := filepath.Walk(root, func(path string, fi os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if !fi.IsDir() {
			// Windows 上链接目录（symlink / junction / 重解析点）在 Lstat 眼里
			// 不是目录，模式位也不一定是 ModeSymlink，只能跟随一次再看。
			if path != root && isLinkedSkillDir(path) {
				found = append(found, Candidate{Dir: path, Name: filepath.Base(path)})
			}
			return nil
		}
		if path != root && IsSkippedDir(fi.Name()) {
			// 隐藏目录不是 Skill——冲突备份之类的东西放这里，不该被当成候选同步上去。
			return filepath.SkipDir
		}
		rel, relErr := filepath.Rel(root, path)
		if relErr == nil {
			depth := len(strings.Split(filepath.ToSlash(rel), "/"))
			if path != root && depth > maxCandidateDepth {
				return filepath.SkipDir
			}
			// 不递归时只留直接子目录（depth 1），再往下一层就停。
			if !recursive && path != root && depth >= 2 {
				return filepath.SkipDir
			}
		}
		if path == root {
			return nil
		}
		if _, err := os.Stat(filepath.Join(path, "SKILL.md")); err == nil {
			found = append(found, Candidate{Dir: path, Name: filepath.Base(path)})
		}
		return nil
	})
	if err != nil {
		return nil, fmt.Errorf("扫描候选目录失败: %w", err)
	}
	return found, nil
}

// isLinkedSkillDir 判断符号链接是不是指向一个 Skill 目录。
//
// 断链、指向普通文件、指向不含 SKILL.md 的目录，都不算。
func isLinkedSkillDir(path string) bool {
	info, err := os.Stat(path)
	if err != nil || !info.IsDir() {
		return false
	}
	_, err = os.Stat(filepath.Join(path, "SKILL.md"))
	return err == nil
}

// SkillName 优先取 SKILL.md frontmatter 的 name，回退到目录名。
func SkillName(dir string) string {
	data, err := os.ReadFile(filepath.Join(dir, "SKILL.md"))
	if err != nil {
		return filepath.Base(dir)
	}
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "name:") {
			name := strings.TrimSpace(strings.TrimPrefix(line, "name:"))
			name = strings.Trim(name, `"'`)
			if name != "" {
				return name
			}
		}
	}
	return filepath.Base(dir)
}
