package skillscan

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// maxCandidateDepth 限制扫描深度，避免在大仓库里走太久。
const maxCandidateDepth = 4

// SkillFile 是描述一个 Skill 的文件名。
const SkillFile = "SKILL.md"

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
//
// 根目录本身就是链接时（多个 Agent 共用一个技能目录就会这样），要跟过去扫：
// filepath.Walk 站在根上是 Lstat，看到的是链接不是目录，一步都走不进去——
// 扫出来会是空的，本地看起来「一个技能都没有」，再叠上删除传播就会把云端清空。
// 候选路径仍按 root 拼：用户看到的是链接那边的路径，基线和日志也都记它。
func Candidates(root string, recursive bool) ([]Candidate, error) {
	sc := &scanContext{root: root, walkRoot: ResolveLink(root), recursive: recursive}
	if err := filepath.Walk(sc.walkRoot, sc.visit); err != nil {
		return nil, fmt.Errorf("扫描候选目录失败: %w", err)
	}
	return sc.found, nil
}

// scanContext 是一次扫描的上下文：扫哪、怎么显示路径、扫到了什么。
type scanContext struct {
	// root 是用户给的扫描根，扫到的路径按它显示。
	root string
	// walkRoot 是实际遍历的目录：root 是链接时是它指向的真实目录。
	walkRoot string
	// recursive 为 false 时只看直接子目录。
	recursive bool
	// found 是扫到的候选。
	found []Candidate
}

// visit 是遍历回调。
func (s *scanContext) visit(path string, fi os.FileInfo, err error) error {
	if err != nil {
		return err
	}
	display := s.display(path)
	if !fi.IsDir() {
		s.collectLinked(display)
		return nil
	}
	return s.collectDir(display, fi)
}

// display 把遍历路径换算成用户看到的路径：根是链接时，路径按 root 拼。
func (s *scanContext) display(path string) string {
	if s.walkRoot == s.root {
		return path
	}
	rel, err := filepath.Rel(s.walkRoot, path)
	if err != nil {
		return path
	}
	return filepath.Join(s.root, rel)
}

// collectLinked 收下链接形态的技能目录。
//
// Windows 上链接目录（symlink / junction / 重解析点）在 Lstat 眼里不是目录，
// 模式位也不一定是 ModeSymlink，只能跟随一次再看。
func (s *scanContext) collectLinked(display string) {
	if display == s.root || !isLinkedSkillDir(display) {
		return
	}
	s.found = append(s.found, Candidate{Dir: display, Name: filepath.Base(display)})
}

// collectDir 收下含 SKILL.md 的目录，该跳过的直接跳过。
func (s *scanContext) collectDir(display string, fi os.FileInfo) error {
	if display != s.root && IsSkippedDir(fi.Name()) {
		// 隐藏目录不是 Skill——冲突备份之类的东西放这里，不该被当成候选同步上去。
		return filepath.SkipDir
	}
	rel, err := filepath.Rel(s.root, display)
	if err == nil {
		depth := len(strings.Split(filepath.ToSlash(rel), "/"))
		if display != s.root && depth > maxCandidateDepth {
			return filepath.SkipDir
		}
		// 不递归时只留直接子目录（depth 1），再往下一层就停。
		if !s.recursive && display != s.root && depth >= 2 {
			return filepath.SkipDir
		}
	}
	if display == s.root {
		return nil
	}
	if _, err := os.Stat(filepath.Join(display, SkillFile)); err == nil {
		s.found = append(s.found, Candidate{Dir: display, Name: filepath.Base(display)})
	}
	return nil
}

// isLinkedSkillDir 判断符号链接是不是指向一个 Skill 目录。
//
// 断链、指向普通文件、指向不含 SKILL.md 的目录，都不算。
func isLinkedSkillDir(path string) bool {
	info, err := os.Stat(path)
	if err != nil || !info.IsDir() {
		return false
	}
	_, err = os.Stat(filepath.Join(path, SkillFile))
	return err == nil
}

// SkillName 优先取 SKILL.md frontmatter 的 name，回退到目录名。
func SkillName(dir string) string {
	data, err := os.ReadFile(filepath.Join(dir, SkillFile))
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
