package cmd

import (
	"errors"
	"net/http"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/installer"
	"github.com/handaome/skillmesh/apps/cli/internal/redact"
)

// 退出码。口径见详细设计 §3.2：脚本按码判断该重试、该换身份，还是该人来拍板。
const (
	ExitOK       = 0
	ExitUnknown  = 1
	ExitUsage    = 2
	ExitAuth     = 3
	ExitDenied   = 4
	ExitNetwork  = 5
	ExitBlocked  = 6
	ExitConflict = 7
	ExitNoTarget = 8
	ExitNotFound = 9
)

// 本地错误的分类哨兵。命令知道「这是哪一类失败」时包一层，
// 分类就不用去猜错误文本——改文案不会连带改坏退出码。
// 冲突那一支复用 sync.go 里的 ErrConflict，不另起一个同义错误。
var (
	ErrNotLoggedIn = errors.New("未登录")
	ErrBlocked     = errors.New("被安全策略阻断")
	ErrNoTarget    = errors.New("目标不可用")
	ErrNotFound    = errors.New("资源不存在")
)

// usageError 判断这是不是「命令用法不对」。
//
// cobra 先校验参数、再跑 PersistentPreRunE，所以命令没走到配置加载
// 就失败，说明是参数个数或 flag 写错了——该退 2，而不是当成运行失败。
func usageError(err error) bool {
	return err != nil && !argsChecked
}

// ExitCode 把错误映射到退出码。
func ExitCode(err error) int {
	if err == nil {
		return ExitOK
	}
	if usageError(err) {
		return ExitUsage
	}
	var problem *apiclient.ProblemError
	if errors.As(err, &problem) {
		return problemExit(problem)
	}
	var network *apiclient.NetworkError
	if errors.As(err, &network) {
		return ExitNetwork
	}
	var conflict *installer.TargetConflictError
	if errors.As(err, &conflict) {
		return ExitConflict
	}
	switch {
	case errors.Is(err, ErrNotLoggedIn):
		return ExitAuth
	case errors.Is(err, ErrBlocked):
		return ExitBlocked
	case errors.Is(err, ErrConflict):
		return ExitConflict
	case errors.Is(err, ErrNoTarget):
		return ExitNoTarget
	case errors.Is(err, ErrNotFound):
		return ExitNotFound
	}
	return ExitUnknown
}

// problemExit 按服务端的错误码定退出码；没登记的码退回按 HTTP 状态判断。
func problemExit(problem *apiclient.ProblemError) int {
	if code, ok := problemExitByCode[problem.Problem.Code]; ok {
		return code
	}
	switch {
	case problem.HTTPStatus == http.StatusUnauthorized:
		return ExitAuth
	case problem.HTTPStatus == http.StatusForbidden:
		return ExitDenied
	case problem.HTTPStatus == http.StatusNotFound:
		return ExitNotFound
	case problem.HTTPStatus == http.StatusConflict:
		return ExitConflict
	case problem.HTTPStatus == http.StatusTooManyRequests:
		return ExitNetwork
	case problem.HTTPStatus >= 500:
		return ExitNetwork
	case problem.HTTPStatus >= 400:
		return ExitUsage
	}
	return ExitUnknown
}

// problemExitByCode 是服务端错误码到退出码的对照。
//
// 按「调用方该怎么反应」分：改参数能解决的是 2，要换身份是 3 和 4，
// 换个时间再试是 5，要人来拍板是 7。
var problemExitByCode = map[string]int{
	"VALIDATION_FAILED":  ExitUsage,
	"INVALID":            ExitUsage,
	"INVITATION_INVALID": ExitUsage,
	"FORBIDDEN":          ExitDenied,
	"NOT_FOUND":          ExitNotFound,
	"ARTIFACT_MISSING":   ExitNotFound,
	"UPLOAD_EXPIRED":     ExitNotFound,
	"SLUG_TAKEN":         ExitConflict,
	"VERSION_CONFLICT":   ExitConflict,
	"SYNC_CONFLICT":      ExitConflict,
	"DIGEST_MISMATCH":    ExitBlocked,
	"RATE_LIMITED":       ExitNetwork,
	"INTERNAL":           ExitUnknown,
}

// errorPayload 是失败时给机器读的那份结果。
//
// 字段直接沿用服务端 problem+json 的口径，不再另造一套错误码词表：
// 同一个 code 在服务端和 CLI 里指的是同一件事，脚本不用记两套映射。
type errorPayload struct {
	OK       bool           `json:"ok"`
	Code     string         `json:"code"`
	Message  string         `json:"message"`
	ExitCode int            `json:"exit_code"`
	Details  map[string]any `json:"details,omitempty"`
}

// errorPayloadOf 把错误变成信封。
func errorPayloadOf(err error) errorPayload {
	payload := errorPayload{
		OK:       false,
		Code:     "UNKNOWN",
		Message:  err.Error(),
		ExitCode: ExitCode(err),
	}
	if usageError(err) {
		payload.Code = "INVALID_ARGUMENTS"
		return payload
	}
	var problem *apiclient.ProblemError
	if errors.As(err, &problem) {
		payload.Code = problem.Problem.Code
		payload.Message = problem.Problem.Detail
		if payload.Message == "" {
			payload.Message = problem.Error()
		}
		details := map[string]any{"http_status": problem.HTTPStatus}
		if problem.Problem.RequestID != "" {
			details["request_id"] = problem.Problem.RequestID
		}
		if len(problem.Problem.FieldErrors) > 0 {
			details["field_errors"] = problem.Problem.FieldErrors
		}
		payload.Details = details
		return payload
	}
	var network *apiclient.NetworkError
	if errors.As(err, &network) {
		// 请求没拿到答复：跟服务端的明确拒绝不是一回事，脚本的处理方式也不同。
		payload.Code = "NETWORK_ERROR"
		payload.Details = map[string]any{"url": redact.Text(network.URL)}
		return payload
	}
	var conflict *installer.TargetConflictError
	if errors.As(err, &conflict) {
		// 目标被占：脚本要能自己判断该换目录还是该让用户确认，
		// 不能去匹配中文文案——改一次文案就断一次。
		payload.Code = "TARGET_CONFLICT"
		payload.Details = map[string]any{
			"target_path": conflict.TargetPath,
			"reason":      conflict.Reason,
		}
		return payload
	}
	switch {
	case errors.Is(err, ErrNotLoggedIn):
		payload.Code = "NOT_LOGGED_IN"
	case errors.Is(err, ErrBlocked):
		payload.Code = "BLOCKED"
	case errors.Is(err, ErrConflict):
		payload.Code = "CONFLICT"
	case errors.Is(err, ErrNoTarget):
		payload.Code = "NO_TARGET"
	case errors.Is(err, ErrNotFound):
		payload.Code = "NOT_FOUND"
	}
	return payload
}
