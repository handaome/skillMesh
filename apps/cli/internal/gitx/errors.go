package gitx

import (
	"fmt"
	"strings"
)

// networkMarkers 是 git 报网络不可达时的特征串。
// 命中就给一句可操作的提示，而不是让用户自己盯着 "Connection was reset" 发呆。
//
// 注意不要放 "unable to access"：那是所有 HTTP 错误的公共前缀，
// 把 404/403 也算成网络问题就会给出错的建议。
var networkMarkers = []string{
	"connection was reset",
	"recv failure",
	"could not resolve host",
	"could not resolve hostname",
	"connection timed out",
	"operation timed out",
	"failed to connect",
	"unable to connect",
	"network is unreachable",
	"empty reply from server",
	"early eof",
	"proxyconnect",
}

// proxyHint 提示怎么给 git 配代理。端口是 Clash for Windows / v2rayN 的常见默认值。
const proxyHint = `如果这台机器访问 GitHub 受限，给 git 配一个代理再试：
  git config --global http.proxy  http://127.0.0.1:7890
  git config --global https.proxy http://127.0.0.1:7890
（Clash for Windows 默认 7890，v2rayN 常见 10809；换成你自己实际用的端口）`

// gitError 组装 git 失败的信息。网络类失败附上代理提示。
func gitError(args []string, err error, output string) error {
	trimmed := strings.TrimSpace(output)
	message := fmt.Errorf("git %s 失败: %w\n%s",
		strings.Join(args, " "), err, trimmed)
	if isNetworkFailure(trimmed) {
		return fmt.Errorf("%w\n\n%s", message, proxyHint)
	}
	return message
}

func isNetworkFailure(output string) bool {
	lower := strings.ToLower(output)
	for _, marker := range networkMarkers {
		if strings.Contains(lower, strings.ToLower(marker)) {
			return true
		}
	}
	return false
}
