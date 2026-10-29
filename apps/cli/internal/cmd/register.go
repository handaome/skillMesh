package cmd

import (
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/auth"
	"github.com/handaome/skillmesh/apps/cli/internal/config"
	"github.com/spf13/cobra"
	"golang.org/x/term"
)

// newRegisterCmd 注册一个内置账号。
//
// 服务端开了自助注册时用这条路：注册完直接就是登录状态。
// 服务端关了（skillmesh.auth.allow-register=false）就由管理员建号。
func newRegisterCmd() *cobra.Command {
	var username, password, displayName, email string
	cmd := &cobra.Command{
		Use:   "register [服务端地址]",
		Short: "注册一个内置账号（用户名 + 密码），注册完直接登录",
		Long: "在服务端注册一个内置账号。位置参数是服务端地址。\n" +
			"密码不给 --password 就在终端里输入（不回显）。\n" +
			"服务端关了自助注册时这条路走不通，让管理员建号。",
		Args: cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			server := Cfg.Server
			if len(args) == 1 {
				server = strings.TrimRight(args[0], "/")
			}
			if username == "" {
				return fmt.Errorf("缺少用户名：skillmesh register --username <用户名>")
			}
			if password == "" {
				password = os.Getenv("SM_PASSWORD")
			}
			if password == "" {
				fmt.Printf("请设置 %s 的密码（不回显）：", username)
				typed, err := term.ReadPassword(int(os.Stdin.Fd()))
				fmt.Println()
				if err != nil {
					return fmt.Errorf("读取密码失败: %w", err)
				}
				password = string(typed)
			}
			tokens, err := apiclient.RegisterAccount(cmd.Context(), server, username, password,
				displayName, email)
			if err != nil {
				return err
			}
			saved := auth.Tokens{
				AccessToken:  tokens.AccessToken,
				RefreshToken: tokens.RefreshToken,
				ExpiresAt:    time.Now().Add(time.Duration(tokens.ExpiresIn) * time.Second),
			}
			path := tokenPath()
			if err := saved.Save(path); err != nil {
				return err
			}
			if err := config.SaveServer(server); err != nil {
				fmt.Fprintf(os.Stderr, "提示：服务端地址没写进配置（%v），后续命令请用 --server 指定\n", err)
			}
			fmt.Printf("已注册并登录（%s），令牌有效期至 %s\n",
				username, saved.ExpiresAt.Local().Format(time.RFC3339))
			return nil
		},
	}
	cmd.Flags().StringVar(&username, "username", "", "要注册的用户名")
	cmd.Flags().StringVar(&password, "password", "", "密码（不给就在终端里输入，不回显）")
	cmd.Flags().StringVar(&displayName, "display-name", "", "显示名（留空就用用户名）")
	cmd.Flags().StringVar(&email, "email", "", "邮箱（可选）")
	return cmd
}
