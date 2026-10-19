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
func Candidates(root string) ([]Candidate, error) {
	var found []Candidate
	err := filepath.Walk(root, func(path string, fi os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if !fi.IsDir() {
			return nil
		}
		if path != root && isIgnored(fi.Name()) {
			return filepath.SkipDir
		}
		rel, relErr := filepath.Rel(root, path)
		if relErr == nil {
			depth := len(strings.Split(filepath.ToSlash(rel), "/"))
			if path != root && depth > maxCandidateDepth {
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
