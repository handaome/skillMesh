package cmd

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/spf13/cobra"
)

// fix-skill：把不合规的 SKILL.md 补成合规的。
//
// 规范（agentskills.io）要求 frontmatter 里有 name 与 description、正文非空。
// 老 skill 常常一个 frontmatter 都没有，于是装不上也发不了云——
// 这个命令补齐它，不用用户自己想该写什么。
//
// 只补缺的，不改已有的：作者写的 name 与描述是它自己的声明，不该被工具改掉。

func newFixSkillCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "fix-skill <dir>",
		Short: "补齐 SKILL.md 的 frontmatter，让它符合 agentskills.io 规范",
		Long: "给缺少 name 或 description 的 SKILL.md 补上 frontmatter。\n" +
			"name 取目录名清洗后的结果，description 取正文第一段。\n" +
			"已有的字段不动。加 --dry-run 只看会改成什么。",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			dir, err := filepath.Abs(args[0])
			if err != nil {
				return err
			}
			fixed, changed, err := fixSkillFile(dir)
			if err != nil {
				return err
			}
			if !changed {
				fmt.Println("已经合规，不用改。")
				return nil
			}
			if globalDryRun {
				fmt.Println("会改成这样（--dry-run 没有落盘）：")
				fmt.Println(fixed)
				return nil
			}
			path := filepath.Join(dir, skillscan.SkillFile)
			if err := os.WriteFile(path, []byte(fixed), 0o644); err != nil {
				return fmt.Errorf("写回失败: %w", err)
			}
			fmt.Printf("已补齐 %s\n", path)
			return nil
		},
	}
	return supportsDryRun(cmd)
}

// fixSkillFile 算出合规后的 SKILL.md 内容。
//
// 返回内容、是否有改动。出错时返回错误——不合规的 skill 不该被悄悄改坏。
//
// 补完必须真的合规：补出来还是不合规就报错，别让用户以为修好了。
func fixSkillFile(dir string) (string, bool, error) {
	path := filepath.Join(dir, skillscan.SkillFile)
	data, err := os.ReadFile(path)
	if err != nil {
		return "", false, fmt.Errorf("读不到 %s: %w", path, err)
	}
	normalized := strings.ReplaceAll(string(data), "\r\n", "\n")

	// 声明了「不按规范校验」的 skill 不用补：它本来就合规。
	if skillscan.ReadMetadata(dir).Exempt() {
		return "", false, nil
	}

	frontmatterText, body, hasFrontmatter := skillscan.SplitFrontmatter(normalized)
	if !hasFrontmatter {
		// 没有 frontmatter（或者有开头没闭合）：整份文件都是正文。
		body = strings.TrimPrefix(normalized, "---\n")
	}
	meta := skillscan.NewFrontmatter(frontmatterText, hasFrontmatter)
	changed := false

	name := meta.GetString("name")
	if name == "" {
		name = cleanName(filepath.Base(dir))
		if name == "" {
			return "", false, fmt.Errorf("目录名是空的，取不到 name：给目录起个名字")
		}
		meta.SetString("name", name)
		changed = true
	} else if len([]rune(name)) > skillscan.MaxNameLen {
		meta.SetString("name", truncate(name, skillscan.MaxNameLen))
		changed = true
	}

	description := meta.GetString("description")
	if description == "" {
		description = firstParagraph(body)
		if description == "" {
			return "", false, fmt.Errorf(
				"正文里没有能当描述的段落，补不了 description：先在 %s 里写一段说明", path)
		}
		meta.SetString("description", description)
		changed = true
	} else if len([]rune(description)) > skillscan.MaxDescriptionLen {
		meta.SetString("description", truncate(description, skillscan.MaxDescriptionLen))
		changed = true
	}

	fixed := meta.Render() + "\n" + strings.TrimLeft(body, "\n")
	// 末尾补一个换行：文件没有结尾换行在别的工具里看着像被截断。
	if !strings.HasSuffix(fixed, "\n") {
		fixed += "\n"
	}
	if err := skillscan.ValidateSpecContent(fixed); err != nil {
		return "", false, fmt.Errorf("补完还是不合规: %w", err)
	}
	return fixed, changed, nil
}

// cleanName 把目录名变成能当 name 的样子。
//
// name 只是显示名，中文没问题；这里只去掉路径分隔符与空白这类一定会出错的字符。
func cleanName(dirName string) string {
	name := strings.TrimSpace(dirName)
	name = strings.ReplaceAll(name, string(filepath.Separator), "-")
	name = strings.ReplaceAll(name, " ", "-")
	name = strings.Trim(name, "-")
	if name == "" {
		return ""
	}
	return truncate(name, skillscan.MaxNameLen)
}

// firstParagraph 取正文的第一段当描述。
//
// 跳过标题行：标题是名字不是描述。去掉 markdown 标记，留下能读懂的一句。
func firstParagraph(body string) string {
	var lines []string
	for _, line := range strings.Split(body, "\n") {
		if strings.HasPrefix(strings.TrimSpace(line), "#") {
			continue
		}
		lines = append(lines, line)
	}
	text := strings.TrimSpace(strings.Join(lines, "\n"))
	if text == "" {
		return ""
	}
	paragraph := text
	if idx := strings.Index(text, "\n\n"); idx >= 0 {
		paragraph = text[:idx]
	}
	plain := strings.TrimSpace(strings.NewReplacer("**", "", "`", "").Replace(paragraph))
	if plain == "" {
		return ""
	}
	return truncate(plain, 200)
}

// truncate 按字符截断，超出时补省略号。
func truncate(text string, limit int) string {
	runes := []rune(text)
	if len(runes) <= limit {
		return text
	}
	if limit <= 1 {
		return string(runes[:limit])
	}
	return string(runes[:limit-1]) + "…"
}
