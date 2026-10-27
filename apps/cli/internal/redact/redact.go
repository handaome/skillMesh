// Package redact 处理要写进日志、审计流水或屏幕上的敏感内容。
//
// 详细设计 §3.9 有两条硬要求：列表默认隐藏可能泄露敏感目录布局的完整绝对路径；
// 诊断输出必须脱敏 Token、私钥、仓库凭据和可能的敏感路径。
//
// 脱敏要保留可定位性——「装到了哪个技能目录」还得看得出来，
// 但完整的目录布局（用户名、盘符、层级）不该躺在日志和审计里，
// 那些东西会被贴到 issue、会被团队管理员看到。
package redact

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"
)

// Path 把路径压成可定位但不可还原的形式。
//
// 家目录换成 ~（家目录下的相对路径保留，那不算泄露布局）；
// 家目录外的绝对路径只留末两段，前面用省略号。
func Path(path string) string {
	if path == "" {
		return ""
	}
	if home, err := os.UserHomeDir(); err == nil && home != "" {
		if path == home {
			return "~"
		}
		if rel, err := filepath.Rel(home, path); err == nil && !strings.HasPrefix(rel, "..") {
			return filepath.Join("~", rel)
		}
	}
	// 反斜杠自己替换而不是用 filepath.ToSlash：后者在 Linux 上不转，
	// 同一份 Windows 路径在两个平台上会脱敏成两个样子。
	parts := strings.Split(strings.ReplaceAll(path, "\\", "/"), "/")
	// 不到四段就没有可藏的：压完等于没压，反而让人看不懂。
	if len(parts) < 4 {
		return path
	}
	return "…/" + strings.Join(parts[len(parts)-2:], "/")
}

// Field 按字段名脱敏。路径类的字段压成片段，其余当自由文本处理。
func Field(key, value string) string {
	switch strings.ToLower(key) {
	case "path", "target_path", "dir", "directory", "config_dir", "cache_dir", "project_root":
		return Path(value)
	}
	return Text(value)
}

var (
	// URL 里内嵌的账号密码：https://user:pass@host
	urlCredential = regexp.MustCompile(`([a-zA-Z][a-zA-Z0-9+.\-]*://)[^/\s:@]+:[^/\s@]+@`)
	// 常见凭据前缀。保留前缀是为了认出是哪类凭据，整段换掉是看不出问题的。
	tokenPrefix = regexp.MustCompile(`\b(ghp_|ghr_|github_pat_|glpat-|glpat_|sk-|sk-ant-|xoxb-|xoxp-|xoxa-|AKIA|AIza|eyJ)[A-Za-z0-9_\-]{8,}`)
	// 邮箱。
	email = regexp.MustCompile(`([A-Za-z0-9._%+\-]+)@([A-Za-z0-9.\-]+\.[A-Za-z]{2,})`)
	// 私钥块。
	privateKey = regexp.MustCompile(`(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----`)
)

// Text 对自由文本脱敏：URL 里的账号密码、凭据、邮箱、私钥块、绝对路径。
func Text(text string) string {
	if text == "" {
		return text
	}
	text = privateKey.ReplaceAllString(text, "[私钥已脱敏]")
	text = urlCredential.ReplaceAllString(text, "${1}***@")
	text = tokenPrefix.ReplaceAllString(text, "${1}***")
	text = email.ReplaceAllString(text, "${1}***@${2}")
	return redactPaths(text)
}

// pathCore 匹配文本里的路径本体：盘符路径、UNC 与根相对的反斜杠路径、
// POSIX 路径、家目录写法。
//
// 边界不在这里判：Go 的正则没有后行断言，「前面是不是文字」得在代码里看。
var pathCore = regexp.MustCompile(
	`(?:[A-Za-z]:[\\/]|\\{1,2}|~[\\/]|/)(?:[^\s"'（）()【】<>|,;]+[\\/])*[^\s"'（）()【】<>|,;]*`)

// redactPaths 把文本里的绝对路径逐个压掉。
//
// 错误消息里的路径不受调用方控制（git 报错、文件操作报错都会带路径），
// 光靠调用方自觉不往 detail 里塞路径是不够的。
func redactPaths(text string) string {
	matches := pathCore.FindAllStringIndex(text, -1)
	if len(matches) == 0 {
		return text
	}
	var sb strings.Builder
	last := 0
	for _, m := range matches {
		path := text[m[0]:m[1]]
		// 双斜杠开头的是 URL 的协议部分，不是路径。
		if strings.HasPrefix(path, "//") {
			continue
		}
		if isDrivePath(path) {
			// 盘符前面紧挨着 ASCII 字母数字，多半是 URL 里的 "s://"，
			// 不是真的盘符路径。
			if prevIsASCIIWord(text, m[0]) {
				continue
			}
		} else if startsMidWord(text, m[0]) {
			// 别的形态要看前一个字：跟在文字后面的多半是句子里带斜杠的说法
			// （「版本/commit」），压了反而把正常文字弄坏。
			continue
		}
		sb.WriteString(text[last:m[0]])
		sb.WriteString(Path(path))
		last = m[1]
	}
	sb.WriteString(text[last:])
	return sb.String()
}

// isDrivePath 判断是不是 Windows 盘符路径。
func isDrivePath(path string) bool {
	return len(path) >= 2 && path[1] == ':'
}

// prevIsASCIIWord 判断路径前面紧挨的是不是 ASCII 字母或数字。
func prevIsASCIIWord(text string, start int) bool {
	if start == 0 {
		return false
	}
	r, _ := utf8.DecodeLastRuneInString(text[:start])
	return r < utf8.RuneSelf && (unicode.IsLetter(r) || unicode.IsDigit(r))
}

// startsMidWord 判断这段路径是不是紧跟在文字后面。
func startsMidWord(text string, start int) bool {
	if start == 0 {
		return false
	}
	r, _ := utf8.DecodeLastRuneInString(text[:start])
	return unicode.IsLetter(r) || unicode.IsDigit(r)
}
