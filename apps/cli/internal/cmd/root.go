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

var cfg config.Config

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
			cfg = loaded
			if server != "" {
				cfg.Server = server
			}
			return nil
		},
	}
	root.PersistentFlags().StringVar(&server, "server", "", "覆盖服务端地址")

	root.AddCommand(
		newLoginCmd(), newLogoutCmd(),
		newSearchCmd(), newInfoCmd(),
		newSourceCmd(), newAddCmd(), newInstallCmd(), newPublishCmd(), newRollbackCmd(),
		newListCmd(), newStatusCmd(), newUninstallCmd(), newSyncCmd(),
		newDoctorCmd(), newAgentsCmd(), newUpdateCmd(), newLockCmd(),
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
func newClient() (*apiclient.Client, error) {
	timeout := time.Duration(cfg.Network.TimeoutSeconds) * time.Second
	if envToken := os.Getenv("SKILLMESH_TOKEN"); envToken != "" {
		return apiclient.New(cfg.Server, envToken, timeout), nil
	}
	tokens, err := auth.LoadTokens(tokenPath())
	if err != nil || !tokens.Valid() {
		return nil, fmt.Errorf("未登录或令牌已过期，请先执行 skillmesh login")
	}
	return apiclient.New(cfg.Server, tokens.AccessToken, timeout), nil
}

func tokenPath() string {
	return filepath.Join(cfg.ConfigDir, "tokens.json")
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
