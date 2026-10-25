package apiclient_test

import (
	"encoding/json"
	apiclient "github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"strings"
	"testing"
)

// 归属与可见性的字段名是跟服务端的契约：写错了服务端不认，
// 而这个错在别的测试里看不出来（请求照样发得出去）。
func TestCreateSkillRequest带上归属字段(t *testing.T) {
	org := "org-1"
	team := "team-1"
	raw, err := json.Marshal(apiclient.CreateSkillRequest{
		Slug: "demo", Name: "Demo", Description: "d", Visibility: "team",
		OrganizationID: &org, OwnerTeamID: &team,
	})
	if err != nil {
		t.Fatal(err)
	}
	body := string(raw)
	for _, want := range []string{`"organization_id":"org-1"`, `"owner_team_id":"team-1"`} {
		if !strings.Contains(body, want) {
			t.Fatalf("请求体缺少 %s，实际 %s", want, body)
		}
	}
}

// 没有归属时这两个字段不能出现——服务端看到空的 organization_id 会当成有。
func TestCreateSkillRequest没有归属时省略字段(t *testing.T) {
	raw, err := json.Marshal(apiclient.CreateSkillRequest{
		Slug: "demo", Name: "Demo", Description: "d", Visibility: "private",
	})
	if err != nil {
		t.Fatal(err)
	}
	body := string(raw)
	for _, unwanted := range []string{"organization_id", "owner_team_id"} {
		if strings.Contains(body, unwanted) {
			t.Fatalf("请求体不该带 %s，实际 %s", unwanted, body)
		}
	}
}
