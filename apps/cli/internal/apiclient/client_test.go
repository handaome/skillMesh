package apiclient

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

// 下载撞上服务端并发上限（429）时按 Retry-After 退避重试，而不是直接失败。
func TestDownloadArtifactRetriesOnRateLimit(t *testing.T) {
	var calls int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if atomic.AddInt32(&calls, 1) == 1 {
			w.Header().Set("Retry-After", "1")
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("zip-bytes"))
	}))
	defer server.Close()

	client := New(server.URL, "token", 5*time.Second)
	data, err := client.DownloadArtifact(context.Background(), "artifact-1")
	if err != nil {
		t.Fatalf("下载失败：%v", err)
	}
	if string(data) != "zip-bytes" {
		t.Fatalf("拿到的字节不对：%q", string(data))
	}
	if atomic.LoadInt32(&calls) != 2 {
		t.Fatalf("应该重试一次，一共 %d 次请求", calls)
	}
}
