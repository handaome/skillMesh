package apiclient

import "fmt"

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
	return fmt.Sprintf("%s (%d): %s", e.Problem.Code, e.HTTPStatus, e.Problem.Detail)
}

// CodeOf 提取服务端错误码；非 problem 响应返回 HTTP_<status>。
func CodeOf(err error) string {
	if pe, ok := err.(*ProblemError); ok {
		return pe.Problem.Code
	}
	return ""
}
