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

// Risk 是一处可疑内容（设计文档 §10 内容扫描：脚本、可疑命令、外部下载、依赖信息）。
//
// 和密钥不同，这些**不阻断**：一份 Skill 里出现 curl 不代表它有害，
// 但装之前应该让人看见。密钥那种高置信度的才拦（见 ScanSecrets）。
type Risk struct {
	Path   string `json:"path"`
	Line   int    `json:"line"`
	Rule   string `json:"rule"`
	Detail string `json:"detail"`
}

// riskRule 是一条可疑内容的特征。
type riskRule struct {
	rule   string
	detail string
	// 只在这些文件里找：脚本和配置，不是所有文本文件都扫
	exts    []string
	exact   []string
	pattern *regexp.Regexp
}

// riskRules 是可疑内容规则。宁可多提示也不漏——用户自己判断。
var riskRules = []riskRule{
	{
		rule: "下载后直接执行", detail: "远程内容被直接管道给解释器执行",
		exts:    []string{".sh", ".bash", ".zsh", ".ps1", ".cmd", ".bat", ".py", ".rb"},
		pattern: regexp.MustCompile(`(?i)(curl|wget|iwr|invoke-webrequest)[^|;&\n]*\|\s*(sudo\s+)?(ba)?sh|iex\s*\(|invoke-expression`),
	},
	{
		rule: "编码后解码执行", detail: "base64/hex 解码后执行，内容不可读",
		exts:    []string{".sh", ".bash", ".ps1", ".cmd", ".bat", ".py"},
		pattern: regexp.MustCompile(`(?i)(base64\s+-d|base64\s+--decode|frombase64string|xxd\s+-r)[^|;&\n]*\|\s*(ba)?sh`),
	},
	{
		rule: "递归删除根目录", detail: "rm -rf 打到了根路径或家目录",
		exts:    []string{".sh", ".bash", ".zsh", ".cmd", ".bat", ".ps1"},
		pattern: regexp.MustCompile(`rm\s+(-[a-zA-Z]*[rf][a-zA-Z]*\s+)+(/|~|\$HOME)(\s|$|/)`),
	},
	{
		rule: "动态求值", detail: "eval/exec 动态执行字符串",
		exts:    []string{".sh", ".bash", ".py", ".rb", ".js", ".ts", ".ps1"},
		pattern: regexp.MustCompile(`(?i)\b(eval|exec)\s*\(`),
	},
	{
		rule: "权限放宽到所有人", detail: "chmod 777 之后目录就人人可写了",
		exts:    []string{".sh", ".bash", ".zsh", ".cmd", ".bat"},
		pattern: regexp.MustCompile(`(?i)chmod\s+(-[a-zA-Z]+\s+)*777`),
	},
	{
		rule: "改权限或提权", detail: "sudo / chmod +s / 修改文件属主",
		exts:    []string{".sh", ".bash", ".zsh", ".ps1", ".cmd", ".bat"},
		pattern: regexp.MustCompile(`(?i)(sudo\s+\S|chmod\s+\+s|chown\s+root)`),
	},
	{
		rule: "关闭安全机制", detail: "跳过证书校验或关掉执行策略",
		exts:    []string{".sh", ".bash", ".ps1", ".py", ".cmd", ".bat"},
		pattern: regexp.MustCompile(`(?i)(curl\s+[^\n]*(-k|--insecure)|set-executionpolicy\s+bypass|NODE_TLS_REJECT_UNAUTHORIZED)`),
	},
	{
		rule: "读取凭据文件", detail: "脚本在读常见凭据位置",
		exts:    []string{".sh", ".bash", ".zsh", ".py", ".ps1", ".rb"},
		pattern: regexp.MustCompile(`(?i)(~\/\.ssh\/id_|\.aws\/credentials|\.netrc|\.npmrc|\.docker\/config\.json|id_rsa)`),
	},
	{
		rule: "安装依赖", detail: "运行时会拉外部依赖，装之前掂量一下",
		exact:   []string{"package.json", "requirements.txt", "pyproject.toml", "Gemfile"},
		pattern: regexp.MustCompile(`(?i)(npm\s+(install|i)\b|pip\s+install\b|gem\s+install\b|go\s+get\b)`),
	},
	{
		rule: "外部下载", detail: "内容里引用了外部地址",
		exts:    []string{".sh", ".bash", ".zsh", ".ps1", ".cmd", ".bat", ".py"},
		pattern: regexp.MustCompile(`https?://[^\s"'<>)]+`),
	},
}

// ScanRisks 扫目录里的可疑内容（设计文档 §10）。
// 只看脚本与配置文件——把每个 .md 里的链接都报一遍只会淹没真问题。
func ScanRisks(dir string) ([]Risk, error) {
	var risks []Risk
	err := filepath.Walk(dir, func(path string, fi os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if fi.IsDir() {
			if path != dir && (isIgnored(fi.Name()) || isHidden(fi.Name())) {
				return filepath.SkipDir
			}
			return nil
		}
		if !fi.Mode().IsRegular() || isHidden(fi.Name()) || fi.Size() > maxScanBytes {
			return nil
		}
		rel, relErr := filepath.Rel(dir, path)
		if relErr != nil {
			rel = path
		}
		rel = filepath.ToSlash(rel)
		found, err := scanRisksIn(path, rel)
		if err != nil {
			return nil // 读不了就跳过，别让一次权限问题毁掉整次扫描
		}
		risks = append(risks, found...)
		return nil
	})
	return risks, err
}

// FormatRisks 把可疑内容整理成人能读、能照着判断的说明。
func FormatRisks(risks []Risk) string {
	if len(risks) == 0 {
		return ""
	}
	var b strings.Builder
	fmt.Fprintf(&b, "发现 %d 处可疑内容（不阻断，装之前请自己判断）：", len(risks))
	seen := map[string]bool{}
	for _, risk := range risks {
		key := risk.Path + ":" + fmt.Sprint(risk.Line) + ":" + risk.Rule
		if seen[key] {
			continue
		}
		seen[key] = true
		fmt.Fprintf(&b, "\n  %s:%d %s —— %s", risk.Path, risk.Line, risk.Rule, risk.Detail)
	}
	return b.String()
}

func scanRisksIn(path, rel string) ([]Risk, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if isBinaryContent(data) {
		return nil, nil
	}
	var risks []Risk
	scanner := bufio.NewScanner(bytes.NewReader(data))
	scanner.Buffer(make([]byte, 0, 64<<10), maxScanBytes)
	for line := 1; scanner.Scan(); line++ {
		text := scanner.Text()
		for _, rule := range riskRules {
			if !rule.appliesTo(rel) {
				continue
			}
			if rule.pattern.MatchString(text) {
				risks = append(risks, Risk{Path: rel, Line: line, Rule: rule.rule, Detail: rule.detail})
			}
		}
	}
	return risks, scanner.Err()
}

func (r riskRule) appliesTo(rel string) bool {
	for _, exact := range r.exact {
		if rel == exact || strings.HasSuffix(rel, "/"+exact) {
			return true
		}
	}
	ext := strings.ToLower(filepath.Ext(rel))
	for _, candidate := range r.exts {
		if ext == candidate {
			return true
		}
	}
	return false
}

func isBinaryContent(data []byte) bool {
	head := data
	if len(head) > 8192 {
		head = head[:8192]
	}
	return bytes.IndexByte(head, 0) >= 0
}
