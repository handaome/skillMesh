package cmd

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"runtime"
	"sort"
	"strings"
	"time"

	"github.com/handaome/skillmesh/apps/cli/internal/auth"
	"github.com/spf13/cobra"
	"golang.org/x/oauth2"
)

// loopbackPort 是 OAuth 回调端口。避开常用端口，且与本机已有服务不冲突。
const loopbackPort = 53682

func newLoginCmd() *cobra.Command {
	var issuer string
	cmd := &cobra.Command{
		Use:   "login [服务端地址]",
		Short: "通过浏览器授权码 + PKCE 登录",
		Long: "登录 SkillMesh。地址可以写成位置参数（设计文档 §7.4 的用法）、" +
			"--issuer，或环境变量 SM_KEYCLOAK_ISSUER。",
		Args: cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			// 位置参数优先，其次 --issuer，最后环境变量
			issuerURL := issuer
			if len(args) == 1 {
				issuerURL = args[0]
			}
			if issuerURL == "" {
				issuerURL = os.Getenv("SM_KEYCLOAK_ISSUER")
			}
			if issuerURL == "" {
				return fmt.Errorf("缺少地址：skillmesh login <服务端地址>，" +
					"或用 --issuer / SM_KEYCLOAK_ISSUER")
			}
			tokens, err := runPkceFlow(cmd.Context(), issuerURL, cfg.ClientID)
			if err != nil {
				return err
			}
			path := tokenPath()
			if err := tokens.Save(path); err != nil {
				return err
			}
			fmt.Printf("已登录，令牌有效期至 %s\n",
				tokens.ExpiresAt.Local().Format(time.RFC3339))
			fmt.Printf("若系统凭据管理器不可用，令牌回退保存在 %s\n", path)
			return nil
		},
	}
	cmd.Flags().StringVar(&issuer, "issuer", "", "OIDC issuer，例如 http://127.0.0.1:8180/kc/realms/skillmesh")
	return cmd
}

func newLogoutCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "logout",
		Short: "清除本地登录态",
		Args:  cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			if err := auth.Clear(tokenPath()); err != nil {
				return err
			}
			fmt.Println("已登出")
			return nil
		},
	}
}

type callbackResult struct {
	code string
	err  error
}

// runPkceFlow 打开浏览器让用户登录，回调端口在环回地址上一次性监听。
// CLI 不接收用户密码（详细设计 §2.1 身份认证）。
func runPkceFlow(ctx context.Context, issuer, clientID string) (auth.Tokens, error) {
	listener, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", loopbackPort))
	if err != nil {
		return auth.Tokens{}, fmt.Errorf("监听回调端口 %d 失败: %w", loopbackPort, err)
	}
	defer listener.Close()

	verifier := auth.NewCodeVerifier()
	state := auth.RandomState()
	redirect := fmt.Sprintf("http://127.0.0.1:%d/callback", loopbackPort)

	done := make(chan callbackResult, 1)
	server := &http.Server{
		ReadHeaderTimeout: 10 * time.Second,
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			query := r.URL.Query()
			if query.Get("state") != state {
				// 不是我们发起的那次回调：回个 400 就完了，别把真正的登录顶掉。
				// 浏览器渲染完回调页会自动请求 /favicon.ico（同样没有 state），
				// 以前这里往 done 里塞错误，用户看到的是一次毫无线索的登录失败。
				http.Error(w, "state 不匹配", http.StatusBadRequest)
				return
			}
			if desc := query.Get("error"); desc != "" {
				http.Error(w, desc, http.StatusBadRequest)
				select {
				case done <- callbackResult{err: fmt.Errorf("授权失败: %s", desc)}:
				default:
				}
				return
			}
			fmt.Fprintln(w, "登录完成，可以关闭这个窗口。")
			// 非阻塞发送：真来了第二个回调（比如浏览器重试）时，
			// 阻塞在这个发送上的 handler 会让 server.Shutdown 永远等下去。
			select {
			case done <- callbackResult{code: query.Get("code")}:
			default:
			}
		}),
	}
	go server.Serve(listener)
	defer server.Shutdown(context.Background())

	issuer = strings.TrimRight(issuer, "/")
	oauthConfig := oauth2.Config{
		ClientID:    clientID,
		RedirectURL: redirect,
		Endpoint: oauth2.Endpoint{
			AuthURL:  issuer + "/protocol/openid-connect/auth",
			TokenURL: issuer + "/protocol/openid-connect/token",
		},
		Scopes: []string{"openid", "profile", "email"},
	}
	authURL := appendQuery(oauthConfig.AuthCodeURL(state, oauth2.AccessTypeOffline), map[string]string{
		"code_challenge":        verifier.Challenge,
		"code_challenge_method": "S256",
	})
	fmt.Println("在浏览器中打开以下地址完成登录：")
	fmt.Println(authURL)
	openBrowser(authURL)

	select {
	case result := <-done:
		if result.err != nil {
			return auth.Tokens{}, result.err
		}
		token, err := oauthConfig.Exchange(ctx, result.code,
			oauth2.SetAuthURLParam("code_verifier", verifier.Verifier))
		if err != nil {
			return auth.Tokens{}, err
		}
		return auth.Tokens{
			AccessToken:  token.AccessToken,
			RefreshToken: token.RefreshToken,
			ExpiresAt:    token.Expiry,
		}, nil
	case <-time.After(5 * time.Minute):
		return auth.Tokens{}, fmt.Errorf("登录超时（5 分钟）")
	}
}

func appendQuery(base string, params map[string]string) string {
	keys := make([]string, 0, len(params))
	for key := range params {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	sep := "?"
	if strings.Contains(base, "?") {
		sep = "&"
	}
	for _, key := range keys {
		base += sep + url.QueryEscape(key) + "=" + url.QueryEscape(params[key])
		sep = "&"
	}
	return base
}

func openBrowser(target string) {
	var command *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		command = exec.Command("rundll32", "url.dll,FileProtocolHandler", target)
	case "darwin":
		command = exec.Command("open", target)
	default:
		command = exec.Command("xdg-open", target)
	}
	_ = command.Start()
}
