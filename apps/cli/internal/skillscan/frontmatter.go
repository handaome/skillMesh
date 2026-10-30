package skillscan

import (
	"strings"

	"gopkg.in/yaml.v3"
)

// Frontmatter 是 SKILL.md 的 frontmatter，可读可改。
//
// 保留原有的键与顺序：作者写的 license、platforms 这些不该被工具重排。
type Frontmatter struct {
	node *yaml.Node
}

// NewFrontmatter 解析 frontmatter 文本。
//
// 没有 frontmatter 或解析不出来时给一个空映射——补齐的时候正是要这个。
func NewFrontmatter(text string, hasFrontmatter bool) *Frontmatter {
	if hasFrontmatter {
		var doc yaml.Node
		if err := yaml.Unmarshal([]byte(text), &doc); err == nil && len(doc.Content) > 0 {
			mapping := doc.Content[0]
			if mapping.Kind == yaml.MappingNode {
				return &Frontmatter{node: mapping}
			}
		}
	}
	return &Frontmatter{node: &yaml.Node{Kind: yaml.MappingNode, Tag: "!!map"}}
}

// GetString 取一个字段的值。不是标量（映射、列表）时返回空串。
func (f *Frontmatter) GetString(key string) string {
	for i := 0; i+1 < len(f.node.Content); i += 2 {
		if f.node.Content[i].Value == key {
			value := f.node.Content[i+1]
			if value.Kind == yaml.ScalarNode {
				return value.Value
			}
			return ""
		}
	}
	return ""
}

// SetString 设一个字段。已有就改，没有就加在末尾。
func (f *Frontmatter) SetString(key, value string) {
	for i := 0; i+1 < len(f.node.Content); i += 2 {
		if f.node.Content[i].Value == key {
			f.node.Content[i+1] = &yaml.Node{
				Kind: yaml.ScalarNode, Tag: "!!str", Value: value}
			return
		}
	}
	f.node.Content = append(f.node.Content,
		&yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: key},
		&yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: value})
}

// Render 渲染成 SKILL.md 开头的那段：--- 包着的 YAML。
func (f *Frontmatter) Render() string {
	out, err := yaml.Marshal(f.node)
	if err != nil {
		return "---\n{}\n---\n"
	}
	return "---\n" + strings.TrimRight(string(out), "\n") + "\n---\n"
}
