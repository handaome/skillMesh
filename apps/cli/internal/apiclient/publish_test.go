package apiclient

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// 建 Skill 要带幂等键：断网重试时服务端认得出是同一次，
// 不会建出第二个同名 Skill（详细设计 §2.1、§4.3 API-001）。
func Test建Skill时带上幂等键(t *testing.T) {
	key := ""
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		key = r.Header.Get("Idempotency-Key")
		_ = json.NewEncoder(w).Encode(map[string]any{"id": "s1", "slug": "demo"})
	}))
	defer server.Close()

	req := CreateSkillRequest{Slug: "demo", Name: "demo", Visibility: "private"}
	client := New(server.URL, "token", 0)
	if _, err := client.CreateSkill(context.Background(), req,
		IdempotencyKey("skills", "sha-1", req)); err != nil {
		t.Fatal(err)
	}
	if key == "" {
		t.Fatal("建 Skill 没带幂等键")
	}
	// 同样的请求要算出同样的键：重试才认得出是同一次。
	if key != IdempotencyKey("skills", "sha-1", req) {
		t.Fatal("同样的请求算出了不同的键")
	}
	// 内容变了就是另一次发布，键也得变。
	if key == IdempotencyKey("skills", "sha-2", req) {
		t.Fatal("内容变了键却没变")
	}
}
