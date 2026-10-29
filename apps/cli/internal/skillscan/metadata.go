package skillscan

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"gopkg.in/yaml.v3"
)

// Metadata 是 SKILL.md frontmatter 里的元信息。
//
// 字段跟 agentskills.io 规范对齐：name、description 必填，其余是可选扩展
// （platforms / tags / license / author / metadata.hermes.*）。
type Metadata struct {
	Name        string         `yaml:"name"`
	Description string         `yaml:"description"`
	Version     string         `yaml:"version"`
	Author      string         `yaml:"author"`
	License     string         `yaml:"license"`
	Platforms   []string       `yaml:"platforms"`
	Tags        []string       `yaml:"tags"`
	Metadata    map[string]any `yaml:"metadata"`
}

// ReadMetadata 读 SKILL.md 的 YAML frontmatter。
// 没有 frontmatter 或解析失败都返回空 Metadata —— 元信息缺失不该挡住发布。
func ReadMetadata(dir string) Metadata {
	data, err := os.ReadFile(filepath.Join(dir, SkillFile))
	if err != nil {
		return Metadata{}
	}
	frontmatter, _, ok := splitFrontmatter(string(data))
	if !ok {
		return Metadata{}
	}
	var meta Metadata
	if err := yaml.Unmarshal([]byte(frontmatter), &meta); err != nil {
		return Metadata{}
	}
	meta.Name = strings.TrimSpace(meta.Name)
	meta.Description = strings.TrimSpace(meta.Description)
	meta.Version = strings.TrimSpace(meta.Version)
	meta.Author = strings.TrimSpace(meta.Author)
	meta.License = strings.TrimSpace(meta.License)
	return meta
}

// Extra 是要随 skill 上云的扩展元信息。
//
// 只放规范里的扩展字段：name/description 已经是 skill 自己的列，
// 再塞一遍会两处对不上。
func (m Metadata) Extra() map[string]any {
	extra := map[string]any{}
	if m.Author != "" {
		extra["author"] = m.Author
	}
	if m.License != "" {
		extra["license"] = m.License
	}
	if len(m.Platforms) > 0 {
		extra["platforms"] = m.Platforms
	}
	if len(m.Tags) > 0 {
		extra["tags"] = m.Tags
	}
	if len(m.Metadata) > 0 {
		extra["metadata"] = m.Metadata
	}
	return extra
}

// SlugFromDirName 把目录名转成 API 接受的 slug。
// 服务端约束是 ^[a-z0-9][a-z0-9-]{1,63}$。
func SlugFromDirName(dirName string) (string, error) {
	slug := strings.ToLower(strings.TrimSpace(dirName))
	slug = strings.ReplaceAll(slug, " ", "-")
	slug = strings.ReplaceAll(slug, "_", "-")
	if slug == "" {
		return "", fmt.Errorf("目录名转不出 slug")
	}
	for _, r := range slug {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '-' {
			continue
		}
		return "", fmt.Errorf("目录名 %q 含非法字符 %q，slug 只允许小写字母、数字和连字符（可用 --slug 显式指定）", dirName, string(r))
	}
	if len(slug) < 2 || len(slug) > 64 {
		return "", fmt.Errorf("slug 长度要在 2-64 之间，实际 %d（可用 --slug 显式指定）", len(slug))
	}
	return slug, nil
}
