// Package gitx 通过系统 git 做来源解析。系统已装 git，不再引 go-git。
package gitx

import (
	"context"
	"fmt"
	"net/url"
	"os/exec"
	"strings"
)

// NormalizeURL 规范化仓库地址，拒绝危险协议与内嵌凭据
// （详细设计 §7.3：凭据不得写入 URL）。
func NormalizeURL(raw string) (string, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return "", fmt.Errorf("仓库地址为空")
	}
	if strings.HasPrefix(strings.ToLower(raw), "file://") {
		return "", fmt.Errorf("拒绝 file:// 协议")
	}
	if strings.HasPrefix(raw, "git@") {
		return raw, nil
	}
	parsed, err := url.Parse(raw)
	if err != nil {
		return "", fmt.Errorf("解析仓库地址失败: %w", err)
	}
	if len(parsed.Scheme) == 1 && len(parsed.Opaque) >= 2 {
		// Windows 本地路径，如 C:\repo
		return "", fmt.Errorf("拒绝本地路径作为来源，请用远程仓库地址")
	}
	switch parsed.Scheme {
	case "https", "ssh", "git", "":
	default:
		return "", fmt.Errorf("只允许 https/ssh/git，实际为 %s", parsed.Scheme)
	}
	// ssh://git@github.com/owner/repo.git 里的 git 是用户名不是凭据，
	// 只有带了密码/token 才该拒。
	if parsed.User != nil {
		if _, hasSecret := parsed.User.Password(); hasSecret {
			return "", fmt.Errorf("仓库地址不得内嵌凭据，请用 --credential-ref 引用本机凭据")
		}
	}
	parsed.Fragment = ""
	return parsed.String(), nil
}

// Clone 把仓库浅克隆到 dest，返回实际解析到的 commit。
func Clone(ctx context.Context, repo, ref, dest string) (string, error) {
	run := func(args ...string) (string, error) {
		cmd := exec.CommandContext(ctx, "git", args...)
		// 禁止 git 弹交互式凭据提示，CLI 不能卡在终端里等用户输密码
		cmd.Env = append(cmd.Environ(), "GIT_TERMINAL_PROMPT=0")
		out, err := cmd.CombinedOutput()
		if err != nil {
			return "", gitError(args, err, string(out))
		}
		return strings.TrimSpace(string(out)), nil
	}

	// 关掉换行自动转换：Skill 里常带 shell 脚本，被转成 CRLF 到了 Unix 上就跑不起来。
	// 这里是要「按提交原样安装」，不是参与日常开发，所以覆盖用户全局设置是对的。
	// 必须写进仓库配置而非用 -c —— checkout 是另一次调用，-c 不会带过去。
	if _, err := run("-C", parentOf(dest), "init", "--quiet", dest); err != nil {
		return "", err
	}
	if _, err := run("-C", dest, "config", "core.autocrlf", "false"); err != nil {
		return "", err
	}
	if _, err := run("-C", dest, "remote", "add", "origin", repo); err != nil {
		return "", err
	}
	fetchArgs := []string{"-C", dest, "fetch", "--depth", "1", "--quiet", "origin"}
	if ref != "" {
		fetchArgs = append(fetchArgs, ref)
	}
	if _, err := run(fetchArgs...); err != nil {
		return "", err
	}
	if _, err := run("-C", dest, "checkout", "--quiet", "FETCH_HEAD"); err != nil {
		return "", err
	}
	return run("-C", dest, "rev-parse", "HEAD")
}

// LsRemote 只取远端 ref 指向的 commit，不下载内容（详细设计 §3.7 检查更新路径）。
func LsRemote(ctx context.Context, repo, ref string) (string, error) {
	args := []string{"ls-remote"}
	if ref == "" {
		args = append(args, repo, "HEAD")
	} else {
		args = append(args, repo, ref, ref)
	}
	cmd := exec.CommandContext(ctx, "git", args...)
	cmd.Env = append(cmd.Environ(), "GIT_TERMINAL_PROMPT=0")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", gitError(args, err, string(out))
	}
	for _, line := range strings.Split(strings.TrimSpace(string(out)), "\n") {
		fields := strings.Fields(line)
		if len(fields) == 2 && len(fields[0]) == 40 {
			return fields[0], nil
		}
	}
	return "", fmt.Errorf("远端未找到 ref: %s", ref)
}

func parentOf(path string) string {
	if idx := strings.LastIndexAny(path, `/\`); idx > 0 {
		return path[:idx]
	}
	return "."
}
