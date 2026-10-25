package gitx_test

import (
	gitx "github.com/handaome/skillmesh/apps/cli/internal/gitx"
	"strings"
	"testing"
)

func Test网络类失败附上代理提示(t *testing.T) {
	output := "fatal: unable to access 'https://github.com/owner/repo/': Recv failure: Connection was reset"
	err := gitx.GitError([]string{"fetch"}, errExit(128), output)

	if !strings.Contains(err.Error(), "Connection was reset") {
		t.Fatalf("应保留 git 原始错误: %v", err)
	}
	if !strings.Contains(err.Error(), "git config --global http.proxy") {
		t.Fatalf("网络类失败应给出代理提示: %v", err)
	}
}

func Test非网络类失败不给代理提示(t *testing.T) {
	output := "fatal: repository 'https://github.com/owner/repo.git/' not found"
	err := gitx.GitError([]string{"fetch"}, errExit(128), output)

	if !strings.Contains(err.Error(), "not found") {
		t.Fatalf("应保留 git 原始错误: %v", err)
	}
	if strings.Contains(err.Error(), "http.proxy") {
		t.Fatalf("非网络类失败不该给代理提示: %v", err)
	}
}

func Test网络失败判定覆盖常见串(t *testing.T) {
	for _, output := range []string{
		"fatal: unable to access 'https://x/': Connection was reset",
		"fatal: unable to access 'https://x/': Recv failure: Connection was reset",
		"ssh: Could not resolve hostname github.com",
		"fatal: unable to access 'https://x/': Failed to connect to github.com port 443",
		"fatal: unable to access 'https://x/': Connection timed out after 21049 ms",
	} {
		if !gitx.IsNetworkFailure(output) {
			t.Fatalf("应判定为网络失败: %s", output)
		}
	}
}

func Test权限与不存在不算网络失败(t *testing.T) {
	// "unable to access" 是所有 HTTP 错误的公共前缀，不能据此判定为网络问题，
	// 否则会给 404/403 错的建议。
	for _, output := range []string{
		"fatal: repository 'https://github.com/owner/repo.git/' not found",
		"fatal: unable to access 'https://x/': The requested URL returned error: 403",
		"fatal: Authentication Failed for 'https://x/'",
	} {
		if gitx.IsNetworkFailure(output) {
			t.Fatalf("不该判定为网络失败: %s", output)
		}
	}
}

type exitError struct{ code int }

func (e exitError) Error() string { return "exit status" }

func errExit(code int) error { return exitError{code} }
