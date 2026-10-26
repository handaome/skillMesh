package skillscan

import (
	"bufio"
	"os"
	"path/filepath"
	"strings"
)

// IgnoreName 是排除规则文件名（设计文档 §6.1）。
const IgnoreName = ".skillmeshignore"

// sensitiveFiles 是凭据类文件名，永远不进包也不进摘要（§6.1「凭据文件」）。
// 这些文件出现在 Skill 里基本等于把密钥打包上传。
var sensitiveFiles = []string{
	".env", ".env.local", ".env.production", ".netrc", ".npmrc", ".pypirc", ".git-credentials",
	"credentials.json", "credentials.yaml", "service-account.json",
	"id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
}

// sensitiveSuffixes 是凭据类扩展名。
var sensitiveSuffixes = []string{
	".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".asc", ".gpg",
}

// IsSensitiveFile 判断一个文件名是不是凭据类文件。
func IsSensitiveFile(name string) bool {
	lower := strings.ToLower(name)
	for _, candidate := range sensitiveFiles {
		if lower == candidate {
			return true
		}
	}
	// .env.xxx 这种动态命名也算
	if strings.HasPrefix(lower, ".env.") {
		return true
	}
	for _, suffix := range sensitiveSuffixes {
		if strings.HasSuffix(lower, suffix) {
			return true
		}
	}
	return false
}

// IsSkippedDir 判断一个目录要不要整目录跳过：忽略目录与隐藏目录。
//
// 打包、算摘要、扫密钥、校验目录必须用同一套规则：发出去的文件与算摘要的文件不是同一批时，
// 审核页看到的文件列表就和作者看到的 diff 对不上，服务端还能扫到本地没扫过的密钥。
func IsSkippedDir(name string) bool {
	return isIgnored(name) || IsHiddenFile(name)
}

// IsHiddenFile 判断文件名是不是隐藏文件（以点开头）。
//
// 隐藏文件是作者的本地状态（.env、.git 之类），不进包、不进摘要、不进安装。
func IsHiddenFile(name string) bool {
	return len(name) > 0 && name[0] == '.'
}

// isIgnored 是要整目录跳过的噪音目录：版本库、依赖目录、编译缓存、虚拟环境。
func isIgnored(name string) bool {
	switch name {
	case ".git", "node_modules", "__pycache__", ".venv", ".idea":
		return true
	}
	return false
}

// IgnoreList 是从一个 Skill 目录读出来的排除规则。
type IgnoreList struct {
	patterns []string
}

// LoadIgnore 读目录下的 .skillmeshignore。没有这个文件就是空规则，不算错误。
func LoadIgnore(dir string) (*IgnoreList, error) {
	file, err := os.Open(filepath.Join(dir, IgnoreName))
	if err != nil {
		return &IgnoreList{}, nil
	}
	defer file.Close()

	list := &IgnoreList{}
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		list.patterns = append(list.patterns, line)
	}
	return list, scanner.Err()
}

// Match 判断相对路径是否被排除。
//
// 规则是 gitignore 的子集：支持 / 开头锚定到根、结尾 / 只管目录、
// * 通配和后缀匹配；不支持 ! 取反和 ** 跨目录——需要的话再加。
func (l *IgnoreList) Match(relative string) bool {
	if l == nil || len(l.patterns) == 0 {
		return false
	}
	clean := filepath.ToSlash(relative)
	for _, pattern := range l.patterns {
		if matchPattern(pattern, clean) {
			return true
		}
	}
	return false
}

func matchPattern(pattern, target string) bool {
	pattern = strings.TrimSuffix(pattern, "/")
	anchored := strings.HasPrefix(pattern, "/")
	pattern = strings.TrimPrefix(pattern, "/")

	if !anchored {
		// 不锚定：任意一层目录里命中都算
		if ok, _ := filepath.Match(pattern, filepath.Base(target)); ok {
			return true
		}
		for _, segment := range strings.Split(target, "/") {
			if ok, _ := filepath.Match(pattern, segment); ok {
				return true
			}
		}
		// 前缀匹配：dist/ 这种写法要能盖住整个目录
		return strings.HasPrefix(target, pattern+"/")
	}

	if ok, _ := filepath.Match(pattern, target); ok {
		return true
	}
	return strings.HasPrefix(target, pattern+"/")
}
