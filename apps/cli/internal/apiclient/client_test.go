package apiclient

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestSearch透传查询参数并解析游标(t *testing.T) {
	var gotQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotQuery = r.URL.RawQuery
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(SkillPage{
			Items:      []Skill{{ID: "s1", Slug: "java-debug", Name: "Java Debug"}},
			NextCursor: ptr("Y3Vyc29y"),
			HasMore:    true,
		})
	}))
	defer srv.Close()

	page, err := New(srv.URL, "tok", 0).Search(context.Background(), "java", "private", "", false, 10, "")
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 1 || page.Items[0].Slug != "java-debug" {
		t.Fatalf("解析结果不对: %+v", page)
	}
	if !page.HasMore || page.NextCursor == nil || *page.NextCursor != "Y3Vyc29y" {
		t.Fatalf("游标解析不对: %+v", page)
	}
	for _, want := range []string{"q=java", "visibility=private", "limit=10"} {
		if !strings.Contains(gotQuery, want) {
			t.Fatalf("查询串缺少 %s，实际 %s", want, gotQuery)
		}
	}
}

func Test非2xx返回带code的ProblemError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/problem+json")
		w.WriteHeader(http.StatusConflict)
		json.NewEncoder(w).Encode(Problem{
			Title: "Version conflict", Status: 409, Code: "VERSION_CONFLICT",
			RequestID: "req_1",
		})
	}))
	defer srv.Close()

	_, err := New(srv.URL, "tok", 0).Skill(context.Background(), "x")
	pe, ok := err.(*ProblemError)
	if !ok {
		t.Fatalf("期望 ProblemError，得到 %T: %v", err, err)
	}
	if pe.Problem.Code != "VERSION_CONFLICT" {
		t.Fatalf("code 不对: %s", pe.Problem.Code)
	}
	if pe.Problem.RequestID != "req_1" {
		t.Fatalf("request_id 不对: %s", pe.Problem.RequestID)
	}
	if CodeOf(err) != "VERSION_CONFLICT" {
		t.Fatalf("CodeOf 不对: %s", CodeOf(err))
	}
}

func Test非JSON错误体也能拿到状态码(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
	}))
	defer srv.Close()

	_, err := New(srv.URL, "tok", 0).Skill(context.Background(), "x")
	if CodeOf(err) != "HTTP_502" {
		t.Fatalf("期望回退到 HTTP_502，得到 %q", CodeOf(err))
	}
}

func Test请求带上Bearer令牌(t *testing.T) {
	var gotAuth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"items":[],"has_more":false}`))
	}))
	defer srv.Close()

	if _, err := New(srv.URL, "secret-token", 0).Search(context.Background(), "", "", "", false, 0, ""); err != nil {
		t.Fatal(err)
	}
	if gotAuth != "Bearer secret-token" {
		t.Fatalf("Authorization 头不对: %q", gotAuth)
	}
}

func ptr(s string) *string { return &s }

// 被限流要退避重试，重试成功就把响应填进结果里。
//
// 原来 doOnce 撞 429 时返回 (true, nil)：doWith 一看 err == nil 就当成功，
// 于是既没重试，响应体也没填——搜索会以为 Skill 不存在，版本列表会当成空列表，
// 用户看到的是一条与「限流」毫无关系的错误。
func Test被限流后退避重试(t *testing.T) {
	calls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if calls == 1 {
			w.Header().Set("Retry-After", "1")
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(SkillPage{
			Items: []Skill{{ID: "s1", Slug: "java-debug"}},
		})
	}))
	defer srv.Close()

	page, err := New(srv.URL, "tok", 0).Search(context.Background(), "java", "private", "", false, 10, "")
	if err != nil {
		t.Fatalf("重试后不该报错: %v", err)
	}
	if calls < 2 {
		t.Fatalf("该重试过，实际请求了 %d 次", calls)
	}
	if len(page.Items) != 1 || page.Items[0].Slug != "java-debug" {
		t.Fatalf("重试后的响应该填进结果里，实际 %+v", page)
	}
}

// 一直限流：重试用完要把错误抛出去，而不是当成成功。
func Test一直限流时报错(t *testing.T) {
	calls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		w.Header().Set("Retry-After", "1")
		w.WriteHeader(http.StatusTooManyRequests)
	}))
	defer srv.Close()

	_, err := New(srv.URL, "tok", 0).Search(context.Background(), "java", "private", "", false, 10, "")
	if err == nil {
		t.Fatal("一直限流该报错")
	}
	if !strings.Contains(err.Error(), "限流") {
		t.Fatalf("错误信息该说明是限流，实际 %v", err)
	}
	if calls < 2 {
		t.Fatalf("该重试过，实际请求了 %d 次", calls)
	}
}
