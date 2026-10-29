package cmd

import (
	"bufio"
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

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/auth"
	"github.com/handaome/skillmesh/apps/cli/internal/config"
	"github.com/spf13/cobra"
	"golang.org/x/oauth2"
	"golang.org/x/term"
)

// loopbackPort 是 OAuth 回调端口。避开常用端口，且与本机已有服务不冲突。
const loopbackPort = 53682

func newLoginCmd() *cobra.Command {
	var manual bool
	var username string
	var password string
	cmd := &cobra.Command{
		Use:   "login [服务端地址]",
		Short: "登录 SkillMesh：内置账号用用户名密码，或走浏览器授权码 + PKCE",
		Long: "登录 SkillMesh。位置参数是服务端地址（设计文档 §7.4 的用法）。\n" +
			"带 --username 走内置账号（用户名 + 密码，不需要浏览器）；\n" +
			"不带则走浏览器授权码 + PKCE，OIDC issuer 从服务端的公开配置里取。\n" +
			"没有图形界面时加 --manual：打印授权地址，手动粘贴回调 URL。",
		Args: cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			server := Cfg.Server
			explicit := len(args) == 1
			if explicit {
				server = strings.TrimRight(args[0], "/")
			}
			// 内置账号：用户名 + 密码。这条路不需要浏览器。
			if username != "" {
				return loginWithPassword(cmd.Context(), server, username, password)
			}
			return runOidcLogin(cmd.Context(), server, explicit, manual)
		},
	}
	cmd.Flags().BoolVar(&manual, "manual", false,
		"无浏览器环境：打印授权地址，手动粘贴回调 URL")
	cmd.Flags().StringVar(&username, "username", "",
		"内置账号的用户名（给了就走用户名密码登录，不用浏览器）")
	cmd.Flags().StringVar(&password, "password", "",
		"内置账号的密码（不给就在终端里输入，不回显）")
	cmd.AddCommand(newLoginStatusCmd())
	return cmd
}

// newLoginStatusCmd 看看当前登录的是谁。
//
// 登录了没、令牌还有效没、服务端认不认——一句话说清。
func newLoginStatusCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "status",
		Short: "看看当前登录的是谁",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			tokens, err := auth.LoadTokens(tokenPath())
			if err != nil {
				return fmt.Errorf("还没登录，请先执行 skillmesh login")
			}
			if !tokens.Valid() {
				tokens, err = refreshTokens(cmd.Context(), tokens)
				if err != nil {
					return err
				}
			}
			timeout := time.Duration(Cfg.Network.TimeoutSeconds) * time.Second
			client := apiclient.New(Cfg.Server, tokens.AccessToken, timeout)
			me, err := client.Me(cmd.Context())
			if err != nil {
				return fmt.Errorf("取当前账号失败: %w", err)
			}
			name := me.User.DisplayName
			if name == "" {
				name = me.User.Subject
			}
			fmt.Printf("已登录：%s\n", name)
			if me.User.Email != "" {
				fmt.Printf("邮箱：%s\n", me.User.Email)
			}
			fmt.Printf("组织：%d 个\n", len(me.Organizations))
			return nil
		},
	}
}

// runOidcLogin 走浏览器授权码 + PKCE。
//
// issuer 的来路有两条：传了服务端地址就从它的公开配置里取；
// 没传就认环境变量 SM_KEYCLOAK_ISSUER。
func runOidcLogin(ctx context.Context, server string, explicit, manual bool) error {
	var issuerURL string
	clientID := Cfg.ClientID
	if explicit {
		// 位置参数是服务端地址：从它的公开配置里取 issuer。
		// 设计文档 §7.4 写的是 `login https://skills.example.com`——
		// 那是服务端，不是 issuer，混用会连错地方。
		cfg, err := apiclient.FetchPublicConfig(ctx, server)
		if err != nil {
			return fmt.Errorf("取服务端公开配置失败（%s）：%w；"+
				"也可以用环境变量 SM_KEYCLOAK_ISSUER 直接指定 issuer", server, err)
		}
		issuerURL = cfg.Issuer
		if cfg.ClientID != "" {
			clientID = cfg.ClientID
		}
		// 记住这台服务端：登录成功后再写进配置，
		// 否则后续 sync 还会连默认的 localhost。
		Cfg.Server = server
	}
	if issuerURL == "" {
		issuerURL = os.Getenv("SM_KEYCLOAK_ISSUER")
	}
	if issuerURL == "" {
		return fmt.Errorf("缺少 issuer：skillmesh login <服务端地址>，" +
			"或设环境变量 SM_KEYCLOAK_ISSUER")
	}
	tokens, err := runPkceFlow(ctx, issuerURL, clientID, manual)
	if err != nil {
		return err
	}
	path := tokenPath()
	if err := tokens.Save(path); err != nil {
		return err
	}
	// 登录成功才写服务端地址：失败时不该把配置改掉。
	if err := config.SaveServer(Cfg.Server); err != nil {
		fmt.Fprintf(os.Stderr, "提示：服务端地址没写进配置（%v），后续命令请用 --server 指定\n", err)
	}
	fmt.Printf("已登录，令牌有效期至 %s\n",
		tokens.ExpiresAt.Local().Format(time.RFC3339))
	fmt.Printf("若系统凭据管理器不可用，令牌回退保存在 %s\n", path)
	return nil
}

// loginWithPassword 用内置账号登录：用户名 + 密码换令牌。
//
// 密码不给 --password 就在终端里输入（不回显）：命令行参数会留在
// shell 历史与进程列表里，密码不该出现在那里。
func loginWithPassword(ctx context.Context, server, username, password string) error {
	if password == "" {
		password = os.Getenv("SM_PASSWORD")
	}
	if password == "" {
		fmt.Printf("请输入 %s 的密码（不回显）：", username)
		typed, err := term.ReadPassword(int(os.Stdin.Fd()))
		fmt.Println()
		if err != nil {
			return fmt.Errorf("读取密码失败: %w", err)
		}
		password = string(typed)
	}
	tokens, err := apiclient.PasswordLogin(ctx, server, username, password)
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
	fmt.Printf("已登录（%s），令牌有效期至 %s\n",
		username, saved.ExpiresAt.Local().Format(time.RFC3339))
	fmt.Printf("若系统凭据管理器不可用，令牌回退保存在 %s\n", path)
	return nil
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
//
// manual 用于没有图形界面的机器：不监听端口，也不开浏览器，
// 由用户把浏览器跳转后的地址粘回来。
func runPkceFlow(ctx context.Context, issuer, clientID string, manual bool) (auth.Tokens, error) {
	verifier := auth.NewCodeVerifier()
	state := auth.RandomState()
	redirect := fmt.Sprintf("http://127.0.0.1:%d/callback", loopbackPort)

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

	if manual {
		return manualCallback(ctx, oauthConfig, verifier, state)
	}
	openBrowser(authURL)

	listener, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", loopbackPort))
	if err != nil {
		return auth.Tokens{}, fmt.Errorf(
			"监听回调端口 %d 失败（无图形界面时加 --manual）: %w", loopbackPort, err)
	}
	defer listener.Close()

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
		return authTokens(token), nil
	case <-time.After(5 * time.Minute):
		return auth.Tokens{}, fmt.Errorf("登录超时（5 分钟）")
	}
}

// manualCallback 让用户粘贴浏览器跳转后的完整地址。
//
// 浏览器跳到的 127.0.0.1:53682 是打开它的那台机器的回环地址，
// 页面通常打不开，但地址栏里带着 code，复制回来就能换令牌。
func manualCallback(ctx context.Context, config oauth2.Config,
	verifier auth.Verifier, state string) (auth.Tokens, error) {

	fmt.Println("登录后浏览器会跳到 http://127.0.0.1:53682/callback?code=...；")
	fmt.Println("页面打不开没关系，把地址栏里的完整 URL 粘贴到这里回车：")

	line, err := bufio.NewReader(os.Stdin).ReadString('\n')
	line = strings.Trim(strings.TrimSpace(line), `"'`)
	if err != nil && line == "" {
		return auth.Tokens{}, fmt.Errorf("读取回调地址失败: %w", err)
	}
	parsed, err := url.Parse(line)
	if err != nil {
		return auth.Tokens{}, fmt.Errorf("回调地址看不懂: %w", err)
	}
	query := parsed.Query()
	if query.Get("state") != state {
		return auth.Tokens{}, fmt.Errorf("state 不匹配：粘贴的可能是另一次登录的地址")
	}
	if desc := query.Get("error"); desc != "" {
		return auth.Tokens{}, fmt.Errorf("授权失败: %s", desc)
	}
	code := query.Get("code")
	if code == "" {
		return auth.Tokens{}, fmt.Errorf("地址里没有 code 参数")
	}
	token, err := config.Exchange(ctx, code,
		oauth2.SetAuthURLParam("code_verifier", verifier.Verifier))
	if err != nil {
		return auth.Tokens{}, err
	}
	return authTokens(token), nil
}

func authTokens(token *oauth2.Token) auth.Tokens {
	return auth.Tokens{
		AccessToken:  token.AccessToken,
		RefreshToken: token.RefreshToken,
		ExpiresAt:    token.Expiry,
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
