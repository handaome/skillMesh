package apiclient

import (
	"fmt"
	"strings"
)

// Problem 对应 problem+json 错误体（详细设计 §2.1）。
type Problem struct {
	Type        string     `json:"type"`
	Title       string     `json:"title"`
	Status      int        `json:"status"`
	Code        string     `json:"code"`
	Detail      string     `json:"detail"`
	RequestID   string     `json:"request_id"`
	Retryable   bool       `json:"retryable"`
	FieldErrors []FieldErr `json:"field_errors"`
}

type FieldErr struct {
	Field   string `json:"field"`
	Code    string `json:"code"`
	Message string `json:"message"`
}

// ProblemError 让调用方按 Code 分支，而不是解析 Detail 文本。
type ProblemError struct {
	HTTPStatus int
	Problem    Problem
}

func (e *ProblemError) Error() string {
	if len(e.Problem.FieldErrors) == 0 {
		return fmt.Sprintf("%s (%d): %s", e.Problem.Code, e.HTTPStatus, e.Problem.Detail)
	}
	// 校验失败时把具体字段带上——只说「校验失败」等于让人自己猜。
	fields := make([]string, 0, len(e.Problem.FieldErrors))
	for _, field := range e.Problem.FieldErrors {
		fields = append(fields, fmt.Sprintf("%s: %s", field.Field, field.Message))
	}
	return fmt.Sprintf("%s (%d): %s（%s）", e.Problem.Code, e.HTTPStatus,
		e.Problem.Detail, strings.Join(fields, "；"))
}

// CodeOf 提取服务端错误码；非 problem 响应返回 HTTP_<status>。
func CodeOf(err error) string {
	if pe, ok := err.(*ProblemError); ok {
		return pe.Problem.Code
	}
	return ""
}
