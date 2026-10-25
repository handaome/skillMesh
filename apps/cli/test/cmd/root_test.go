package cmd_test

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/auth"
	cmd "github.com/handaome/skillmesh/apps/cli/internal/cmd"
)

// jwtLike 造一个 JWT 形状的串，只有 payload 是真的——续期靠 iss 找 token 端点。
func jwtLike(claims map[string]any) string {
	payload, _ := json.Marshal(claims)
	return "header." + base64.RawURLEncoding.EncodeToString(payload) + ".sig"
}

// 令牌过期时，CLI 要能自己续上——常驻同步（watch/daemon）全靠它。
func TestRenewedTokens过期后续期(t *testing.T) {
	var gotClient, gotGrant string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err == nil {
			gotClient = r.PostForm.Get("client_id")
			gotGrant = r.PostForm.Get("grant_type")
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"access_token":"new-access","refresh_token":"new-refresh",
			"token_type":"Bearer","expires_in":3600}`))
	}))
	defer srv.Close()

	tokens := auth.Tokens{
		AccessToken:  jwtLike(map[string]any{"iss": srv.URL}),
		RefreshToken: "old-refresh",
		ExpiresAt:    time.Now().Add(-time.Hour),
	}
	fresh, err := cmd.RenewedTokens(context.Background(), tokens, "skillmesh-cli")
	if err != nil {
		t.Fatalf("不该报错: %v", err)
	}
	if gotGrant != "refresh_token" {
		t.Fatalf("该用 refresh_token 授权，实际 %q", gotGrant)
	}
	if gotClient != "skillmesh-cli" {
		t.Fatalf("该用配置的 client，实际 %q", gotClient)
	}
	if fresh.AccessToken != "new-access" || fresh.RefreshToken != "new-refresh" {
		t.Fatalf("没换成新令牌: %+v", fresh)
	}
	if !fresh.ExpiresAt.After(time.Now()) {
		t.Fatalf("过期时间该是将来: %v", fresh.ExpiresAt)
	}
}

// 这几种情况都不该"续期成功"，报错要说清人该做什么。
func TestRenewedTokens续不了时的报错(t *testing.T) {
	// 没有 refresh token
	_, err := cmd.RenewedTokens(context.Background(), auth.Tokens{AccessToken: jwtLike(map[string]any{"iss": "http://x"})}, "skillmesh-cli")
	if err == nil || !strings.Contains(err.Error(), "skillmesh login") {
		t.Fatalf("没有 refresh token 该提示重新登录，实际 %v", err)
	}

	// access token 不是 JWT：取不到 issuer
	_, err = cmd.RenewedTokens(context.Background(), auth.Tokens{AccessToken: "not-a-jwt", RefreshToken: "r"}, "skillmesh-cli")
	if err == nil || !strings.Contains(err.Error(), "issuer") {
		t.Fatalf("取不到 issuer 该报错，实际 %v", err)
	}

	// token 端点拒绝：续期失败
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte(`{"error":"invalid_grant"}`))
	}))
	defer srv.Close()
	_, err = cmd.RenewedTokens(context.Background(), auth.Tokens{
		AccessToken: jwtLike(map[string]any{"iss": srv.URL}), RefreshToken: "r",
	}, "skillmesh-cli")
	if err == nil || !strings.Contains(err.Error(), "续期失败") {
		t.Fatalf("端点拒绝该报续期失败，实际 %v", err)
	}
}

// login 的位置参数是服务端地址（设计文档 §7.4），
// issuer 要从服务端的公开配置里取——不能拿服务端地址当 issuer 用。
func TestLogin从服务端公开配置取issuer(t *testing.T) {
	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"issuer":"http://127.0.0.1:8180/kc/realms/skillmesh","client_id":"skillmesh-cli"}`))
	}))
	defer srv.Close()

	cfg, err := apiclient.FetchPublicConfig(context.Background(), srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	if gotPath != "/api/v1/config/public" {
		t.Fatalf("该请求公开配置端点，实际 %s", gotPath)
	}
	if cfg.Issuer != "http://127.0.0.1:8180/kc/realms/skillmesh" {
		t.Fatalf("issuer 取错了：%q", cfg.Issuer)
	}
	if cfg.ClientID != "skillmesh-cli" {
		t.Fatalf("client_id 取错了：%q", cfg.ClientID)
	}
}
