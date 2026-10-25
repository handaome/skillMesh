package cmd_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	cmd "github.com/handaome/skillmesh/apps/cli/internal/cmd"
)

// 巡检的端到端：读本机磁盘 → 算状态 → 写回服务端。
//
// 单元测试钉住了状态判定，这个用例钉住三件事串起来是对的：
// 巡检只动本机的记录、真状态真的写回去了、写回去的是算出来的那个状态。
func Test巡检把真实状态写回服务端(t *testing.T) {
	// 本机装着一份内容，服务端记的版本也在云端。
	local := writeSkill(t, filepath.Join(t.TempDir(), "demo"), "# demo\n")

	var pushed apiclient.UpdateInstallationInput
	// 目标路径是本机绝对路径（Windows 上带反斜杠），得用 JSON 编码不能拼字符串。
	listed, err := json.Marshal([]map[string]any{{
		"id": "inst-1", "skill_id": "skill-1", "device_id": "dev-1",
		"agent": "claude-code", "install_scope": "user", "target_path": local,
		"installed_version": "1.0.0", "status": "active", "local_modified": false,
	}})
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/installations":
			w.Write(listed)
		case r.Method == http.MethodGet && r.URL.Path == "/skills/skill-1/versions":
			// 云端那一版带清单：清单摘要与本机算的不一样 → 判「本地改过」。
			w.Write([]byte(`{"items":[{"version":"1.0.0","status":"published",` +
				`"manifest":[{"path":"SKILL.md","sha256":"` + fakeSHA + `"}],` +
				`"artifact":{"id":"art-1","sha256":"` + fakeSHA + `"}}],"has_more":false}`))
		case r.Method == http.MethodPost && r.URL.Path == "/devices":
			w.Write([]byte(`{"id":"dev-1"}`))
		case r.Method == http.MethodPatch && r.URL.Path == "/installations/inst-1":
			if err := json.NewDecoder(r.Body).Decode(&pushed); err != nil {
				t.Error(err)
			}
			w.Write([]byte(`{"id":"inst-1"}`))
		default:
			w.WriteHeader(http.StatusInternalServerError)
		}
	}))
	defer srv.Close()

	rows, err := apiclient.New(srv.URL, "tok", 0).Installations(context.Background(), "")
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 {
		t.Fatalf("该拿到一条安装记录，得到 %d 条", len(rows))
	}

	out, notes := cmd.CheckInstallations(context.Background(), apiclient.New(srv.URL, "tok", 0), rows)
	if len(notes) > 0 {
		t.Logf("巡检说明: %v", notes)
	}
	if len(out) != 1 {
		t.Fatalf("巡检后应该还是一条记录，得到 %d 条", len(out))
	}
	// 本机目录里 SKILL.md 还在，服务端记的那一版在云端也是 published —— 状态是 active。
	if out[0].Status != "active" {
		t.Fatalf("状态该是 active，得到 %s", out[0].Status)
	}
	// 本机内容跟云端那一版对不上（云端的摘要跟本机算的不一样）→ 算「本地改过」，
	// 这个结论要写回服务端——不然服务端的 local_modified 永远是 false。
	if pushed.LocalModified == nil || !*pushed.LocalModified {
		t.Fatalf("该把「本地改过」写回服务端，得到 %+v", pushed)
	}
}

// 假摘要：云端那一版的清单摘要。本机内容与它对不上 → 判为本地改过。
const fakeSHA = "0000000000000000000000000000000000000000000000000000000000000000"
