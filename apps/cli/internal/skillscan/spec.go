package skillscan

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"gopkg.in/yaml.v3"
)

// agentskills.io 规范的硬性限制。Hermes 这类按规范扫的 Agent 直接照这三条判，
// 不合规的 skill 装上去也不会被识别。
const (
	// MaxNameLen 是 name 的字符数上限。
	MaxNameLen = 64
	// MaxDescriptionLen 是 description 的字符数上限。
	MaxDescriptionLen = 1024
	// MaxSkillFileChars 是整个 SKILL.md 的字符数上限。
	MaxSkillFileChars = 100000
)

// ValidateSpec 按 agentskills.io 规范校验一个 Skill 目录。
//
// 规范之外没有 frontmatter 的 skill 在 Hermes 这类 Agent 里根本不会被扫到：
// 装上去看不见，用户只会以为 SkillMesh 装坏了。与其这样，不如装之前说清缺什么。
//
// 只查 SKILL.md 本身。文件大小、数量那些限制归 ValidateDir。
func ValidateSpec(dir string) error {
	data, err := os.ReadFile(filepath.Join(dir, SkillFile))
	if err != nil {
		return fmt.Errorf("缺少 %s，不是合法的 Skill 目录", SkillFile)
	}
	content := string(data)
	if n := len([]rune(content)); n > MaxSkillFileChars {
		return fmt.Errorf("%s 共 %d 字符，超过上限 %d", SkillFile, n, MaxSkillFileChars)
	}
	normalized := strings.ReplaceAll(content, "\r\n", "\n")
	if !strings.HasPrefix(normalized, "---\n") {
		return fmt.Errorf("%s 开头必须是 --- 包裹的 YAML frontmatter", SkillFile)
	}
	frontmatter, body, ok := splitFrontmatter(normalized)
	if !ok {
		return fmt.Errorf("%s 的 frontmatter 没有闭合", SkillFile)
	}
	var meta Metadata
	if err := yaml.Unmarshal([]byte(frontmatter), &meta); err != nil {
		return fmt.Errorf("%s 的 frontmatter 不是合法 YAML：%v", SkillFile, err)
	}
	if meta.Name == "" {
		return fmt.Errorf("%s 的 frontmatter 缺 name", SkillFile)
	}
	if n := len([]rune(meta.Name)); n > MaxNameLen {
		return fmt.Errorf("%s 的 name 有 %d 个字符，超过上限 %d", SkillFile, n, MaxNameLen)
	}
	if meta.Description == "" {
		return fmt.Errorf("%s 的 frontmatter 缺 description", SkillFile)
	}
	if n := len([]rune(meta.Description)); n > MaxDescriptionLen {
		return fmt.Errorf("%s 的 description 有 %d 个字符，超过上限 %d",
			SkillFile, n, MaxDescriptionLen)
	}
	if strings.TrimSpace(body) == "" {
		return fmt.Errorf("%s 只有 frontmatter，没有正文", SkillFile)
	}
	return nil
}

// splitFrontmatter 取出开头 --- ... --- 之间的 YAML 与之后的正文。
//
// 闭合标记认的是行首的 ---（后跟换行或文件结束），免得正文里的分隔线
// 把 frontmatter 提前截断。
func splitFrontmatter(content string) (string, string, bool) {
	normalized := strings.ReplaceAll(content, "\r\n", "\n")
	if !strings.HasPrefix(normalized, "---\n") {
		return "", "", false
	}
	rest := normalized[len("---\n"):]
	end := strings.Index(rest, "\n---")
	if end < 0 {
		return "", "", false
	}
	frontmatter := rest[:end]
	body := rest[end+len("\n---"):]
	body = strings.TrimPrefix(body, "\n")
	return frontmatter, body, true
}
