package apiclient_test

import (
	"context"
	"encoding/json"
	apiclient "github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"net/http"
	"net/http/httptest"
	"testing"
)

// 回收站：列出被删掉的文件、恢复其中一个。
//
// 服务端的契约：GET /skills/{id}/recycle 列出，POST /skills/{id}/recycle/restore 恢复。
func Test回收站列出与恢复(t *testing.T) {
	var restored string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/skills/skill-1/recycle":
			w.Write([]byte(`[{"path":"old.md","sha256":"abc",` +
				`"source_version":"1.0.0","deleted_at":"2026-10-01T00:00:00Z",` +
				`"expires_at":"2026-10-31T00:00:00Z"}]`))
		case r.Method == http.MethodPost && r.URL.Path == "/skills/skill-1/recycle/restore":
			var body map[string]string
			json.NewDecoder(r.Body).Decode(&body)
			restored = body["path"]
			w.Write([]byte(`{"skill_id":"skill-1","version":"1.0.2",` +
				`"file_count":2,"sha256":"def","status":"published"}`))
		default:
			w.WriteHeader(http.StatusInternalServerError)
		}
	}))
	defer srv.Close()

	client := apiclient.New(srv.URL, "tok", 0)
	rows, err := client.RecycleFiles(context.Background(), "skill-1")
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].Path != "old.md" {
		t.Fatalf("该列出一个被删的文件，得到 %+v", rows)
	}
	if rows[0].SourceVersion != "1.0.0" {
		t.Errorf("该带上来源版本，得到 %q", rows[0].SourceVersion)
	}

	result, err := client.RestoreFile(context.Background(), "skill-1", "old.md")
	if err != nil {
		t.Fatal(err)
	}
	if restored != "old.md" {
		t.Errorf("恢复的路径该传上去，得到 %q", restored)
	}
	if result.Version != "1.0.2" || result.Status != "published" {
		t.Errorf("该报出产出的版本与状态，得到 %+v", result)
	}
}
