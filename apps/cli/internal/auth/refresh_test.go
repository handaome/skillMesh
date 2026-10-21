package auth

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// jwtLike 造一个 JWT 形状的串（header.payload.signature），只有 payload 是真的。
func jwtLike(claims map[string]any) string {
	payload, _ := json.Marshal(claims)
	enc := base64.RawURLEncoding.EncodeToString(payload)
	return "header." + enc + ".sig"
}

func TestIssuerFromToken(t *testing.T) {
	token := jwtLike(map[string]any{"iss": "http://127.0.0.1:8180/kc/realms/skillmesh"})
	issuer, err := IssuerFromToken(token)
	if err != nil {
		t.Fatalf("不该报错: %v", err)
	}
	if issuer != "http://127.0.0.1:8180/kc/realms/skillmesh" {
		t.Fatalf("issuer 取错了: %q", issuer)
	}

	// 不是 JWT、payload 里没有 iss、payload 坏掉——三种都要报错而不是给出空串
	if _, err := IssuerFromToken("not-a-jwt"); err == nil {
		t.Fatal("不是 JWT 该报错")
	}
	if _, err := IssuerFromToken(jwtLike(map[string]any{"sub": "x"})); err == nil {
		t.Fatal("没有 iss 该报错")
	}
	if _, err := IssuerFromToken("a.!!!not-base64!!!.c"); err == nil {
		t.Fatal("payload 坏掉该报错")
	}
}

func TestRefresh(t *testing.T) {
	var gotGrant, gotClient string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// 路径要钉住：拼错成 .../tokens 的话真实 Keycloak 会回 404，
		// 而这里对任何路径都回 200，测试就白绿了。
		if r.URL.Path != "/protocol/openid-connect/token" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		if err := r.ParseForm(); err == nil {
			gotGrant = r.PostForm.Get("grant_type")
			gotClient = r.PostForm.Get("client_id")
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"access_token":"new-access","refresh_token":"new-refresh",
			"token_type":"Bearer","expires_in":3600}`))
	}))
	defer srv.Close()

	tokens := Tokens{
		AccessToken:  "old-access",
		RefreshToken: "old-refresh",
		ExpiresAt:    time.Now().Add(-time.Hour),
	}
	fresh, err := Refresh(context.Background(), "skillmesh-cli", srv.URL, tokens)
	if err != nil {
		t.Fatalf("不该报错: %v", err)
	}
	if gotGrant != "refresh_token" {
		t.Fatalf("该用 refresh_token 授权，实际 %q", gotGrant)
	}
	// public client：client_id 要在表单体里，不能只塞进 Basic 头
	if gotClient != "skillmesh-cli" {
		t.Fatalf("表单里该有 client_id，实际 %q", gotClient)
	}
	if fresh.AccessToken != "new-access" || fresh.RefreshToken != "new-refresh" {
		t.Fatalf("没换成新令牌: %+v", fresh)
	}
	if !fresh.ExpiresAt.After(time.Now()) {
		t.Fatalf("过期时间该是将来: %v", fresh.ExpiresAt)
	}

	// 没有 refresh token 就不该去换
	if _, err := Refresh(context.Background(), "skillmesh-cli", srv.URL, Tokens{}); err == nil {
		t.Fatal("没有 refresh token 该报错")
	}
	if _, err := Refresh(context.Background(), "skillmesh-cli", "", tokens); err == nil {
		t.Fatal("没有 issuer 该报错")
	}
}
