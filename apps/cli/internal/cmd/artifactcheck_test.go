package cmd

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
)

// zip 头认得出，gzip 头认不出。
func TestIsZipArtifact(t *testing.T) {
	cases := []struct {
		name string
		head []byte
		want bool
	}{
		{"zip", []byte("PK\x03\x04rest"), true},
		{"gzip", []byte{0x1f, 0x8b, 0x08, 0x00}, false},
		{"空", nil, false},
		{"太短", []byte("PK"), false},
	}
	for _, item := range cases {
		if got := isZipArtifact(item.head); got != item.want {
			t.Fatalf("%s：isZipArtifact(% X) = %v，want %v", item.name, item.head, got, item.want)
		}
	}
}

// 假服务端：一个人云端空间里两个 Skill，一个制品是 zip，一个不是。
func artifactServer(t *testing.T) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()
	mux.HandleFunc("/skills", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"items":[
			{"id":"s1","slug":"good","name":"好的"},
			{"id":"s2","slug":"bad","name":"坏的"}],
			"has_more":false}`))
	})
	mux.HandleFunc("/skills/s1/versions", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"items":[
			{"id":"v1","version":"1.0.0","status":"published",
			 "artifact":{"id":"a1","size_bytes":10}}]}`))
	})
	mux.HandleFunc("/skills/s2/versions", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"items":[
			{"id":"v2","version":"1.0.0","status":"published",
			 "artifact":{"id":"a2","size_bytes":10}},
			{"id":"v3","version":"0.9.0","status":"draft",
			 "artifact":{"id":"a3","size_bytes":10}}]}`))
	})
	mux.HandleFunc("/artifacts/a1/download", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("PK\x03\x04payload"))
	})
	mux.HandleFunc("/artifacts/a2/download", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte{0x1f, 0x8b, 0x08, 0x00})
	})
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)
	return server
}

// 体检只报真正坏的那一个：草稿不算，已发布但不是 zip 的才算。
func TestCheckCloudArtifactsFindsBroken(t *testing.T) {
	server := artifactServer(t)
	client := apiclient.New(server.URL, "token", 0)
	broken, err := checkCloudArtifacts(context.Background(), client)
	if err != nil {
		t.Fatal(err)
	}
	if len(broken) != 1 {
		t.Fatalf("查出 %d 个坏制品，want 1：%v", len(broken), broken)
	}
	if broken[0].Skill.Slug != "bad" || broken[0].Version.Version != "1.0.0" {
		t.Fatalf("查出的是 %s@%s，want bad@1.0.0",
			broken[0].Skill.Slug, broken[0].Version.Version)
	}
}
