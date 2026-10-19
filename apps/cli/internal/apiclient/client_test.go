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
