package cmd

import (
	"errors"
	"fmt"
	"strings"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
)

func TestExitCode(t *testing.T) {
	// 命令跑到一半才失败时，参数校验早就过了。
	argsChecked = true
	cases := []struct {
		name string
		err  error
		want int
	}{
		{"没有错误", nil, ExitOK},
		{"未登录", fmt.Errorf("%w，请先执行 skillmesh login", ErrNotLoggedIn), ExitAuth},
		{"冲突", fmt.Errorf("%w（版本不同）", ErrConflict), ExitConflict},
		{"安全策略阻断", fmt.Errorf("%w：命中密钥扫描", ErrBlocked), ExitBlocked},
		{"目标不可用", fmt.Errorf("%w：目录不可写", ErrNoTarget), ExitNoTarget},
		{"资源不存在", fmt.Errorf("%w：装不了", ErrNotFound), ExitNotFound},
		{"网络失败", &apiclient.NetworkError{URL: "https://s/api/v1/skills", Err: errors.New("timeout")}, ExitNetwork},
		{"服务端校验失败", problem(400, "VALIDATION_FAILED"), ExitUsage},
		{"服务端权限不足", problem(403, "FORBIDDEN"), ExitDenied},
		{"服务端资源不存在", problem(404, "NOT_FOUND"), ExitNotFound},
		{"服务端冲突", problem(409, "SYNC_CONFLICT"), ExitConflict},
		{"服务端限流", problem(429, "RATE_LIMITED"), ExitNetwork},
		{"服务端内部错误", problem(500, "INTERNAL"), ExitUnknown},
		{"未登记的错误码按状态判", problem(401, "HTTP_401"), ExitAuth},
		{"没分类的错误", errors.New("炸了"), ExitUnknown},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := ExitCode(c.err); got != c.want {
				t.Fatalf("ExitCode(%v) = %d，期望 %d", c.err, got, c.want)
			}
		})
	}
}

func TestErrorPayloadPassesProblemThrough(t *testing.T) {
	err := &apiclient.ProblemError{
		HTTPStatus: 400,
		Problem: apiclient.Problem{
			Code:    "VALIDATION_FAILED",
			Detail:  "slug 不合法",
			RequestID: "req-123",
			FieldErrors: []apiclient.FieldErr{
				{Field: "slug", Code: "INVALID", Message: "只能用小写字母、数字和连字符"},
			},
		},
	}
	payload := errorPayloadOf(err)
	if payload.OK {
		t.Fatal("失败结果的 ok 应为 false")
	}
	if payload.Code != "VALIDATION_FAILED" {
		t.Fatalf("code = %q，期望沿用服务端的 VALIDATION_FAILED", payload.Code)
	}
	if payload.Message != "slug 不合法" {
		t.Fatalf("message = %q，期望原样透出服务端 detail", payload.Message)
	}
	if payload.ExitCode != ExitUsage {
		t.Fatalf("exit_code = %d，期望 %d", payload.ExitCode, ExitUsage)
	}
	if payload.Details["request_id"] != "req-123" {
		t.Fatalf("details 里应有 request_id，实际 %v", payload.Details)
	}
	if _, ok := payload.Details["field_errors"]; !ok {
		t.Fatalf("details 里应有 field_errors，实际 %v", payload.Details)
	}
}

func TestErrorPayloadLocal(t *testing.T) {
	payload := errorPayloadOf(fmt.Errorf("%w，请先执行 skillmesh login", ErrNotLoggedIn))
	if payload.Code != "NOT_LOGGED_IN" {
		t.Fatalf("code = %q，期望 NOT_LOGGED_IN", payload.Code)
	}
	if payload.ExitCode != ExitAuth {
		t.Fatalf("exit_code = %d，期望 %d", payload.ExitCode, ExitAuth)
	}
	if payload.Details != nil {
		t.Fatalf("本地错误不该带 details，实际 %v", payload.Details)
	}
}

// 请求没拿到答复要与「服务端明确拒绝」分开：前者多半该重试，后者不是。
func TestErrorPayloadNetwork(t *testing.T) {
	err := &apiclient.NetworkError{
		URL: "https://alice:secret@example.com/api/v1/skills",
		Err: errors.New("timeout"),
	}
	payload := errorPayloadOf(err)
	if payload.Code != "NETWORK_ERROR" {
		t.Fatalf("code = %q，期望 NETWORK_ERROR", payload.Code)
	}
	if payload.ExitCode != ExitNetwork {
		t.Fatalf("exit_code = %d，期望 %d", payload.ExitCode, ExitNetwork)
	}
	if url, _ := payload.Details["url"].(string); strings.Contains(url, "alice:secret") {
		t.Fatalf("details 里的 URL 不该带凭据，实际 %q", url)
	}
}

func TestUsageErrorExitCode(t *testing.T) {
	// 命令连参数校验都没过就失败了，退出码该是 2 而不是 1。
	argsChecked = false
	defer func() { argsChecked = true }()

	err := errors.New("accepts 1 arg(s), received 0")
	if got := ExitCode(err); got != ExitUsage {
		t.Fatalf("ExitCode(%v) = %d，期望 %d", err, got, ExitUsage)
	}
	payload := errorPayloadOf(err)
	if payload.Code != "INVALID_ARGUMENTS" {
		t.Fatalf("code = %q，期望 INVALID_ARGUMENTS", payload.Code)
	}
	if payload.ExitCode != ExitUsage {
		t.Fatalf("exit_code = %d，期望 %d", payload.ExitCode, ExitUsage)
	}
}

func problem(status int, code string) error {
	return &apiclient.ProblemError{HTTPStatus: status, Problem: apiclient.Problem{Code: code, Detail: code}}
}
