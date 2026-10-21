package skillscan

import (
	"bufio"
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// Finding 是一处疑似真实凭据的位置。
type Finding struct {
	Path string // 相对 Skill 根目录
	Line int
	Rule string
}

// secretRule 是一条高置信度的凭据特征。
type secretRule struct {
	name    string
	pattern *regexp.Regexp
}

// secretRules 只收误报极低的特征。文档里的占位符（xxx、your-key-here）不该被拦住，
// 而真密钥长得都很固定——宁可漏报，也不要制造一堆噪声让人习惯性忽略告警。
var secretRules = []secretRule{
	{"AWS Access Key", regexp.MustCompile(`AKIA[0-9A-Z]{16}`)},
	{"Google API Key", regexp.MustCompile(`AIza[0-9A-Za-z_\-]{35}`)},
	{"GitHub Token", regexp.MustCompile(`gh[pousr]_[A-Za-z0-9]{36,}`)},
	{"OpenAI Key", regexp.MustCompile(`sk-[A-Za-z0-9]{20,}`)},
	{"Slack Token", regexp.MustCompile(`xox[baprs]-[A-Za-z0-9-]{10,}`)},
	{"私钥文件内容", regexp.MustCompile(`-----BEGIN [A-Z ]*PRIVATE KEY-----`)},
}

// maxScanBytes 单个文件超过这个大小就不扫了——凭据不会藏在几百 MB 的二进制里。
const maxScanBytes = 2 << 20

// ScanSecrets 扫 Skill 目录里的文本文件，返回疑似真实凭据的位置。
func ScanSecrets(dir string) ([]Finding, error) {
	var findings []Finding
	err := filepath.Walk(dir, func(path string, fi os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if fi.IsDir() {
			if path != dir && IsSkippedDir(fi.Name()) {
				return filepath.SkipDir
			}
			return nil
		}
		if fi.Size() > maxScanBytes {
			return nil
		}
		rel, relErr := filepath.Rel(dir, path)
		if relErr != nil {
			rel = path
		}
		found, err := scanFile(path, filepath.ToSlash(rel))
		if err != nil {
			return nil // 读不了的单个文件不该中断整个扫描
		}
		findings = append(findings, found...)
		return nil
	})
	return findings, err
}

// FormatFindings 把命中整理成人能读、能照着改的说明。
func FormatFindings(findings []Finding) string {
	var b strings.Builder
	fmt.Fprintf(&b, "扫描到 %d 处疑似真实凭据：", len(findings))
	for _, finding := range findings {
		fmt.Fprintf(&b, "\n  %s:%d  %s", finding.Path, finding.Line, finding.Rule)
	}
	b.WriteString("\nSkill 包里不该有密钥。请删掉或改成占位符后重试。")
	return b.String()
}

func scanFile(path, rel string) ([]Finding, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if isBinary(data) {
		return nil, nil
	}
	var findings []Finding
	scanner := bufio.NewScanner(bytes.NewReader(data))
	scanner.Buffer(make([]byte, 0, 64<<10), maxScanBytes)
	for line := 1; scanner.Scan(); line++ {
		text := scanner.Text()
		for _, rule := range secretRules {
			if rule.pattern.MatchString(text) {
				findings = append(findings, Finding{Path: rel, Line: line, Rule: rule.name})
			}
		}
	}
	return findings, scanner.Err()
}

// isBinary 按前 8KB 有没有 NUL 字节判断，跟 git 判二进制一个路子。
func isBinary(data []byte) bool {
	head := data
	if len(head) > 8192 {
		head = head[:8192]
	}
	return bytes.Contains(head, []byte{0})
}
