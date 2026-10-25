package cmd_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	cmd "github.com/handaome/skillmesh/apps/cli/internal/cmd"
)

// --skill 给 slug 时按 slug 查，给 id 时直接当 id 用。
//
// 服务端的 slug 规则不接受 UUID：拿 id 当 slug 查会被拒。
func Test回收站的Skill标识两种都认(t *testing.T) {
	var searched string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/skills":
			searched = r.URL.RawQuery
			w.Write([]byte(`{"items":[{"id":"skill-1","slug":"demo","name":"demo"}],` +
				`"has_more":false}`))
		case r.Method == http.MethodGet && r.URL.Path == "/skills/skill-1/recycle":
			w.Write([]byte(`[]`))
		default:
			w.WriteHeader(http.StatusInternalServerError)
		}
	}))
	defer srv.Close()
	client := apiclient.New(srv.URL, "tok", 0)

	// 给 slug：查出来拿 id。
	id, err := cmd.ResolveSkillID(context.Background(), client, "demo")
	if err != nil {
		t.Fatal(err)
	}
	if id != "skill-1" {
		t.Errorf("按 slug 查该拿到 skill-1，得到 %q", id)
	}
	if searched == "" {
		t.Error("按 slug 该走一次搜索")
	}

	// 给 id：直接用，不该再去搜。
	searched = ""
	raw := "64c43e2f-4846-4509-b6e0-7353631cb1f4"
	id, err = cmd.ResolveSkillID(context.Background(), client, raw)
	if err != nil {
		t.Fatal(err)
	}
	if id != raw {
		t.Errorf("给 id 该原样用，得到 %q", id)
	}
	if searched != "" {
		t.Errorf("给 id 不该再发搜索请求，却发了 %q", searched)
	}
}
