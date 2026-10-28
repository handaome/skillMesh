// Package cmd 是 CLI 的命令层。
package cmd

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/auth"
	"github.com/handaome/skillmesh/apps/cli/internal/config"
	"github.com/spf13/cobra"
)

var Cfg config.Config

// timeoutCancel 取消 --timeout 设的那个整体超时。
//
// 不能在 PersistentPreRunE 里 defer：那个函数一返回，RunE 还没跑，
// 上下文就被取消了，命令会莫名其妙地失败。
var timeoutCancel context.CancelFunc

// argsChecked 记录命令的参数校验是否已经通过。
//
// cobra 先校验参数、再跑 PersistentPreRunE，所以「没走到配置加载就失败」
// 就意味着是参数或 flag 写错了，不该按运行失败分类。
var argsChecked bool

func newRootCmd() *cobra.Command {
	var server, project, profile, timeout string
	var noColor, assumeYes bool
	argsChecked = false
	root := &cobra.Command{
		Use:           "skillmesh",
		Short:         "SkillMesh CLI：安装、同步与发布 Agent Skill",
		SilenceUsage:  true,
		SilenceErrors: true,
		PersistentPreRunE: func(c *cobra.Command, _ []string) error {
			loaded, err := config.LoadProfile(profile)
			if err != nil {
				return err
			}
			Cfg = loaded
			if server != "" {
				Cfg.Server = server
			}
			if project != "" {
				if err := setProjectRoot(project); err != nil {
					return err
				}
			}
			if err := applyTimeout(c, timeout); err != nil {
				return err
			}
			argsChecked = true
			// 还没支持 JSON 的命令要提前说清楚：用户加了 --json 却拿到
			// 一屏人读表格，比没加 --json 更让人以为程序坏了。
			if jsonOut && c.Annotations[jsonAnnotation] != "true" {
				fmt.Fprintln(os.Stderr, "提示：该命令暂不支持 --json，输出仍是人读格式。")
			}
			return nil
		},
	}
	root.PersistentFlags().StringVar(&server, "server", "", "覆盖服务端地址")
	root.PersistentFlags().StringVar(&project, "project", "", "指定项目根目录（默认从当前目录向上找）")
	root.PersistentFlags().StringVar(&profile, "profile", "", "使用指定的配置档（配置目录下的 profiles/<名字>.yaml）")
	root.PersistentFlags().StringVar(&timeout, "timeout", "", "命令整体超时，如 30s、2m")
	root.PersistentFlags().BoolVar(&noColor, "no-color", false, "禁用 ANSI 色彩（本 CLI 输出本来就没有色彩）")
	root.PersistentFlags().BoolVar(&assumeYes, "yes", false, "对可安全自动确认的提示采用默认值（不绕过安全阻断与破坏性操作）")
	root.PersistentFlags().BoolVar(&jsonOut, "json", false, "输出稳定 JSON，供脚本调用")
	root.PersistentFlags().BoolVar(&quiet, "quiet", false, "减少普通提示，错误仍输出到 stderr")
	root.PersistentFlags().BoolVar(&verbose, "verbose", false, "显示平时隐藏的完整绝对路径")
	root.Version = cliVersion

	root.AddCommand(
		newLoginCmd(), newLogoutCmd(),
		newSearchCmd(), newInfoCmd(),
		newSourceCmd(), NewAddCmd(), newInstallCmd(), newPublishCmd(),
		newReviewCmd(),
		newListCmd(), newUninstallCmd(), newInstallationsCmd(),
		newScanCmd(), newSyncCmd(), newCloudCmd(), newGroupCmd(),
		newDoctorCmd(), NewUpdateCmd(), newTrashCmd(),
		newLogsCmd(), newSelfInstallCmd(),
		newWatchCmd(),
	)
	hideAdvancedCommands(root)
	return root
}

// applyTimeout 给本次命令加一个整体超时。
//
// 超时是命令级的：过了点整个命令停，不是单个请求超时——
// 单个请求的超时在配置里的 network.timeout_seconds。
func applyTimeout(c *cobra.Command, raw string) error {
	if raw == "" {
		return nil
	}
	duration, err := time.ParseDuration(raw)
	if err != nil {
		return fmt.Errorf("--timeout 不是合法的时长（%q）：%w", raw, err)
	}
	if duration <= 0 {
		return fmt.Errorf("--timeout 必须是正时长，收到 %q", raw)
	}
	ctx, cancel := context.WithTimeout(c.Context(), duration)
	timeoutCancel = cancel
	c.SetContext(ctx)
	return nil
}

// hideAdvancedCommands 把边缘命令从 --help 里收起来。
//
// 顶层 29 个命令里，日常「多地同步」只用得上 login / publish / sync / search /
// info / install / list / uninstall。其余是团队治理、Git 来源、运维排障的边角；
// 一次性列出来只会让人以为要先学一遍。
//
// 只是从帮助里隐藏，命令本身照旧可用（脚本、文档里的用法都不坏）：
// 需要时用 skillmesh help <命令> 查，或看 docs/cli-使用手册.md。
func hideAdvancedCommands(root *cobra.Command) {
	advanced := map[string]bool{
		"add": true, "cloud": true, "doctor": true,
		"group": true, "installations": true, "logs": true,
		"review": true, "scan": true, "self-install": true,
		"source": true, "trash": true, "update": true, "watch": true,
	}
	for _, sub := range root.Commands() {
		if advanced[sub.Name()] {
			sub.Hidden = true
		}
	}
}

func Execute() error {
	defer func() {
		if timeoutCancel != nil {
			timeoutCancel()
		}
	}()
	if err := newRootCmd().ExecuteContext(context.Background()); err != nil {
		reportError(err)
		return err
	}
	return nil
}

// reportError 把失败写成机器可读的信封，或一行人话。
//
// 两种模式都往 stderr 写：stdout 只留给成功结果，下游读到的永远是干净的。
func reportError(err error) {
	if jsonEnabled() {
		encoder := json.NewEncoder(os.Stderr)
		encoder.SetIndent("", "  ")
		_ = encoder.Encode(errorPayloadOf(err))
		return
	}
	fmt.Fprintln(os.Stderr, "错误:", err)
	if usageError(err) {
		fmt.Fprintln(os.Stderr, "用法：skillmesh <命令> --help")
	}
}

// newClient 用已保存的登录态建 API 客户端。
//
// SKILLMESH_TOKEN 是给 CI 用的入口（详细设计 §3.10 要求 CI 跑
// `skillmesh install --locked`）：流水线里既没有浏览器也没有凭据管理器，
// 只能靠环境变量传令牌。交互场景仍走 `skillmesh login` 存进凭据管理器，
// 不受这个变量影响。
func newClient(ctx context.Context) (*apiclient.Client, error) {
	timeout := time.Duration(Cfg.Network.TimeoutSeconds) * time.Second
	if envToken := os.Getenv("SKILLMESH_TOKEN"); envToken != "" {
		return apiclient.New(Cfg.Server, envToken, timeout), nil
	}
	tokens, err := auth.LoadTokens(tokenPath())
	if err != nil {
		return nil, fmt.Errorf("%w，请先执行 skillmesh login", ErrNotLoggedIn)
	}
	if !tokens.Valid() {
		tokens, err = refreshTokens(ctx, tokens)
		if err != nil {
			return nil, err
		}
	}
	return apiclient.New(Cfg.Server, tokens.AccessToken, timeout), nil
}

// refreshTokens 令牌过期时用 refresh token 换新的，并把新令牌存回去。
//
// 常驻同步（watch）不能因为令牌过期就静默停摆：原来这里直接报「令牌已过期」，
// 而 watch 每轮同步都走这里，于是同步会一直失败、只在日志里留一行。
func refreshTokens(ctx context.Context, tokens auth.Tokens) (auth.Tokens, error) {
	// 令牌端点不按规矩回话时不能无限挂着：watch 会卡在这儿，
	// 既不听文件变化也不同步——正是这个功能要解决的那类停摆。给续期一个上限。
	timeout := time.Duration(Cfg.Network.TimeoutSeconds) * time.Second
	if timeout <= 0 {
		timeout = 30 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	fresh, err := RenewedTokens(ctx, tokens, Cfg.ClientID)
	if err != nil {
		return tokens, err
	}
	if err := fresh.Save(tokenPath()); err != nil {
		return fresh, fmt.Errorf("续期成功但保存新令牌失败: %w", err)
	}
	return fresh, nil
}

// RenewedTokens 是续期这件事本身：换一个新的 access token 回来。
//
// 单独拆出来是为了能测——它不碰凭据管理器，也不依赖全局配置。
// issuer 从 access token 的 iss 声明里取：登录时没把它存下来，但令牌自己知道是哪个 realm 签的。
func RenewedTokens(ctx context.Context, tokens auth.Tokens, clientID string) (auth.Tokens, error) {
	if tokens.RefreshToken == "" {
		return tokens, fmt.Errorf("令牌已过期，请先执行 skillmesh login")
	}
	if clientID == "" {
		clientID = "skillmesh-cli"
	}
	issuer, err := auth.IssuerFromToken(tokens.AccessToken)
	if err != nil {
		return tokens, fmt.Errorf("令牌已过期且取不到 issuer，请重新执行 skillmesh login: %w", err)
	}
	fresh, err := auth.Refresh(ctx, clientID, issuer, tokens)
	if err != nil {
		return tokens, fmt.Errorf("令牌已过期且续期失败，请重新执行 skillmesh login: %w", err)
	}
	return fresh, nil
}

func tokenPath() string {
	return filepath.Join(Cfg.ConfigDir, "tokens.json")
}

// mustGetwd 返回当前目录，失败时返回空串（调用方据此判定无项目根）。
func mustGetwd() string {
	dir, err := os.Getwd()
	if err != nil {
		return ""
	}
	return dir
}

func orDash(s string) string {
	if s == "" {
		return "-"
	}
	return s
}

func short(sha string) string {
	if len(sha) > 8 {
		return sha[:8]
	}
	return orDash(sha)
}
