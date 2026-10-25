// Package cmd 是 CLI 的命令层。
package cmd

import (
	"context"
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

func newRootCmd() *cobra.Command {
	var server string
	root := &cobra.Command{
		Use:           "skillmesh",
		Short:         "SkillMesh CLI：安装、同步与发布 Agent Skill",
		SilenceUsage:  true,
		SilenceErrors: true,
		PersistentPreRunE: func(_ *cobra.Command, _ []string) error {
			loaded, err := config.Load()
			if err != nil {
				return err
			}
			Cfg = loaded
			if server != "" {
				Cfg.Server = server
			}
			return nil
		},
	}
	root.PersistentFlags().StringVar(&server, "server", "", "覆盖服务端地址")

	root.AddCommand(
		newLoginCmd(), newLogoutCmd(),
		newSearchCmd(), newInfoCmd(),
		newSourceCmd(), NewAddCmd(), newInstallCmd(), newPublishCmd(), newRollbackCmd(),
		newReviewCmd(),
		newListCmd(), newStatusCmd(), newUninstallCmd(), newInstallationsCmd(),
		newScanCmd(), newSyncCmd(), newRestoreCmd(),
		newDoctorCmd(), newAgentsCmd(), NewUpdateCmd(), newLockCmd(), newTrashCmd(),
		newRecycleCmd(),
		newWatchCmd(), newDaemonCmd(),
	)
	return root
}

func Execute() error {
	if err := newRootCmd().ExecuteContext(context.Background()); err != nil {
		fmt.Fprintln(os.Stderr, "错误:", err)
		return err
	}
	return nil
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
		return nil, fmt.Errorf("未登录，请先执行 skillmesh login")
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
// 常驻同步（watch/daemon）不能因为令牌过期就静默停摆：原来这里直接报「令牌已过期」，
// 而 watch 每轮同步都走这里，于是同步会一直失败、只在日志里留一行。
func refreshTokens(ctx context.Context, tokens auth.Tokens) (auth.Tokens, error) {
	// 令牌端点不按规矩回话时不能无限挂着：watch/daemon 会卡在这儿，
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
