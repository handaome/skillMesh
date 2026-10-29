package skillscan

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"gopkg.in/yaml.v3"
)

// Metadata 是 SKILL.md frontmatter 里的元信息。
type Metadata struct {
	Name        string `yaml:"name"`
	Description string `yaml:"description"`
	Version     string `yaml:"version"`
}

// ReadMetadata 读 SKILL.md 的 YAML frontmatter。
// 没有 frontmatter 或解析失败都返回空 Metadata —— 元信息缺失不该挡住发布。
func ReadMetadata(dir string) Metadata {
	data, err := os.ReadFile(filepath.Join(dir, SkillFile))
	if err != nil {
		return Metadata{}
	}
	frontmatter, ok := extractFrontmatter(string(data))
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
	return meta
}

// extractFrontmatter 取出文件开头 --- ... --- 之间的 YAML。
func extractFrontmatter(content string) (string, bool) {
	normalized := strings.ReplaceAll(content, "\r\n", "\n")
	if !strings.HasPrefix(normalized, "---\n") {
		return "", false
	}
	rest := normalized[len("---\n"):]
	end := strings.Index(rest, "\n---")
	if end < 0 {
		return "", false
	}
	return rest[:end], true
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
