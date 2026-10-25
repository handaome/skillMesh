package cmd_test

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	cmd "github.com/handaome/skillmesh/apps/cli/internal/cmd"
)

func TestResolvePublishScope(t *testing.T) {
	orgs := []apiclient.OrgMembership{
		{ID: "org-1", Slug: "acme", Name: "Acme", Role: "owner"},
		{ID: "org-2", Slug: "other", Name: "Other", Role: "member"},
	}
	teams := []apiclient.Team{
		{ID: "team-1", OrganizationID: "org-1", Slug: "platform", Name: "Platform", Status: "active"},
	}

	t.Run("不指定组织时只用原可见性", func(t *testing.T) {
		scope, err := cmd.ResolvePublishScope(nil, teams, "", "public")
		if err != nil {
			t.Fatalf("不该报错: %v", err)
		}
		if scope.Visibility != "public" || scope.OrganizationID != "" {
			t.Fatalf("不该带组织信息: %+v", scope)
		}
	})

	t.Run("只给组织时可见性是 organization", func(t *testing.T) {
		scope, err := cmd.ResolvePublishScope(&orgs[0], teams, "", "private")
		if err != nil {
			t.Fatalf("不该报错: %v", err)
		}
		if scope.Visibility != "organization" || scope.OrganizationID != "org-1" {
			t.Fatalf("可见性定错了: %+v", scope)
		}
	})

	t.Run("给了团队时可见性是 team 且带上两个 id", func(t *testing.T) {
		scope, err := cmd.ResolvePublishScope(&orgs[0], teams, "platform", "private")
		if err != nil {
			t.Fatalf("不该报错: %v", err)
		}
		if scope.Visibility != "team" || scope.OrganizationID != "org-1" || scope.TeamID != "team-1" {
			t.Fatalf("可见性定错了: %+v", scope)
		}
	})

	t.Run("只给团队没给组织是错", func(t *testing.T) {
		if _, err := cmd.ResolvePublishScope(nil, teams, "platform", "private"); err == nil {
			t.Fatal("应该报错")
		}
	})

	t.Run("组织不存在时报错并列出可选项", func(t *testing.T) {
		_, err := cmd.FindOrganization(orgs, "nope")
		if err == nil {
			t.Fatal("应该报错")
		}
		if !strings.Contains(err.Error(), "acme") {
			t.Fatalf("错误信息里应列出可见组织: %v", err)
		}
	})

	t.Run("团队不存在时报错", func(t *testing.T) {
		if _, err := cmd.ResolvePublishScope(&orgs[0], teams, "nope", "private"); err == nil {
			t.Fatal("应该报错")
		}
	})

	t.Run("角色不够发布时拒绝", func(t *testing.T) {
		if _, err := cmd.ResolvePublishScope(&orgs[1], teams, "", "private"); err == nil {
			t.Fatal("member 不该能往组织发布")
		}
	})

	t.Run("非 active 的团队不能作为发布目标", func(t *testing.T) {
		archived := []apiclient.Team{
			{ID: "team-9", OrganizationID: "org-1", Slug: "old", Name: "Old", Status: "archived"},
		}
		_, err := cmd.ResolvePublishScope(&orgs[0], archived, "old", "private")
		if err == nil {
			t.Fatal("归档的团队不该能收新版本")
		}
		if !strings.Contains(err.Error(), "archived") {
			t.Fatalf("错误信息里应说明团队状态: %v", err)
		}
	})
}

func TestRoleAtLeast(t *testing.T) {
	cases := []struct {
		actual, required string
		want             bool
	}{
		{"owner", "publisher", true},
		{"publisher", "publisher", true},
		{"reviewer", "publisher", false},
		{"member", "member", true},
		{"viewer", "member", false},
		{"", "member", false},
	}
	for _, c := range cases {
		if got := cmd.RoleAtLeast(c.actual, c.required); got != c.want {
			t.Errorf("RoleAtLeast(%q, %q) = %v, 想要 %v", c.actual, c.required, got, c.want)
		}
	}
}

func TestCheckScopeFlags(t *testing.T) {
	if err := cmd.CheckScopeFlags("", ""); err != nil {
		t.Fatalf("不该报错: %v", err)
	}
	if err := cmd.CheckScopeFlags("acme", "platform"); err != nil {
		t.Fatalf("不该报错: %v", err)
	}
	if err := cmd.CheckScopeFlags("", "platform"); err == nil {
		t.Fatal("只给团队没给组织应该报错")
	}
}

func TestCheckVisibilityFlag(t *testing.T) {
	cases := []struct {
		name              string
		orgSlug, teamSlug string
		requested         string
		wantErr           bool
	}{
		{"没显式给可见性就随便", "", "", "", false},
		{"private 不需要组织", "", "", "private", false},
		{"public 不需要组织", "", "", "public", false},
		{"team 可见要配组织", "", "", "team", true},
		{"organization 可见要配组织", "", "", "organization", true},
		{"组织可见配 organization", "acme", "", "organization", false},
		{"组织可见配 private 是冲突", "acme", "", "private", true},
		{"团队可见配 team", "acme", "platform", "team", false},
		{"团队可见没给团队是错", "acme", "", "team", true},
		{"团队可见配 organization 是冲突", "acme", "platform", "organization", true},
	}
	for _, c := range cases {
		err := cmd.CheckVisibilityFlag(c.orgSlug, c.teamSlug, c.requested)
		if (err != nil) != c.wantErr {
			t.Errorf("%s: err = %v, 想要报错 %v", c.name, err, c.wantErr)
		}
	}
}

// 已有 Skill 的归属与可见性改不动，带着新要求发布要报错而不是假装生效。
func TestCheckExistingSkill(t *testing.T) {
	skill := apiclient.Skill{Slug: "demo", Visibility: "public"}

	if err := cmd.CheckExistingSkill(skill, cmd.PublishScope{}, ""); err != nil {
		t.Fatalf("没提要求不该报错: %v", err)
	}
	if err := cmd.CheckExistingSkill(skill, cmd.PublishScope{}, "public"); err != nil {
		t.Fatalf("显式要的就是现值，不该报错: %v", err)
	}
	if err := cmd.CheckExistingSkill(skill, cmd.PublishScope{}, "private"); err == nil {
		t.Fatal("可见性改不了，应该报错")
	}
	if err := cmd.CheckExistingSkill(skill, cmd.PublishScope{OrganizationID: "org-1"}, ""); err == nil {
		t.Fatal("已有 Skill 不能改归属，应该报错")
	}
	if err := cmd.CheckExistingSkill(skill, cmd.PublishScope{TeamID: "team-1"}, ""); err == nil {
		t.Fatal("已有 Skill 不能改归属，应该报错")
	}
}

// 发 v2 时再带上一样的 --org/--team 是主流程：归属一致就该放行，
// 归属对不上才拦，而且要说清这个 Skill 属于谁、你要发到哪、怎么办。
func TestCheckExistingSkill比对归属(t *testing.T) {
	teamSkill := apiclient.Skill{Slug: "demo", Visibility: "team",
		OrganizationID: "org-1", OwnerTeamID: "team-1"}
	sameScope := cmd.PublishScope{OrganizationID: "org-1", OrganizationSlug: "acme",
		TeamID: "team-1", TeamSlug: "platform", Visibility: "team"}

	if err := cmd.CheckExistingSkill(teamSkill, sameScope, ""); err != nil {
		t.Fatalf("同一个归属不该报错: %v", err)
	}
	if err := cmd.CheckExistingSkill(teamSkill, cmd.PublishScope{}, ""); err != nil {
		t.Fatalf("不带 scope 时该沿用它已登记的归属: %v", err)
	}

	err := cmd.CheckExistingSkill(teamSkill,
		cmd.PublishScope{OrganizationID: "org-2", OrganizationSlug: "other"}, "")
	if err == nil {
		t.Fatal("发到别的组织应该报错")
	}
	for _, want := range []string{"org-1", "other", "--org/--team"} {
		if !strings.Contains(err.Error(), want) {
			t.Fatalf("错误信息里该有 %q，实际 %v", want, err)
		}
	}

	// 个人 Skill 不能靠 --org 变成组织的。
	if err := cmd.CheckExistingSkill(apiclient.Skill{Slug: "demo", Visibility: "private"},
		cmd.PublishScope{OrganizationID: "org-1", OrganizationSlug: "acme"}, ""); err == nil {
		t.Fatal("个人 Skill 加上组织应该报错")
	}

	// 同一个组织里团队对不上：两边都要说 slug，不是 UUID——
	// 同一句话里「你要发到 组织 acme」用 slug、「它已登记在 组织 <UUID>」用 UUID，用户会懵。
	otherTeam := cmd.CheckExistingSkill(teamSkill, cmd.PublishScope{
		OrganizationID: "org-1", OrganizationSlug: "acme",
		TeamID: "team-2", TeamSlug: "platform", Visibility: "team",
		Teams: []apiclient.Team{
			{ID: "team-1", Slug: "core"},
			{ID: "team-2", Slug: "platform"},
		},
	}, "")
	if otherTeam == nil {
		t.Fatal("同一个组织不同团队应该报错")
	}
	for _, want := range []string{"组织 acme", "团队 core", "团队 platform"} {
		if !strings.Contains(otherTeam.Error(), want) {
			t.Fatalf("错误信息里该有 %q，实际 %v", want, otherTeam)
		}
	}
}

// sync 这类内部调用不给 Scope，可见性得回落到 options.Visibility。
func TestPublishOptions可见性回落(t *testing.T) {
	if got := (cmd.PublishOptions{Visibility: "private"}).VisibilityFor(); got != "private" {
		t.Fatalf("没有 Scope 时该用原可见性，得到 %q", got)
	}
	options := cmd.PublishOptions{Visibility: "private", Scope: cmd.PublishScope{Visibility: "team"}}
	if got := options.VisibilityFor(); got != "team" {
		t.Fatalf("有 Scope 时该用 Scope 的可见性，得到 %q", got)
	}
}

// demoSkillDir 造一个最小的 Skill 目录。
func demoSkillDir(t *testing.T) string {
	t.Helper()
	dir := filepath.Join(t.TempDir(), "demo")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	content := "---\nname: demo\ndescription: 演示用的 Skill\n---\n\n# demo\n"
	if err := os.WriteFile(filepath.Join(dir, "SKILL.md"), []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	return dir
}

// PublishOne 的两条分支：新建时把归属带进请求；已有 Skill 时不假装能改归属。
func TestPublishOne归属与可见性(t *testing.T) {
	t.Run("新建时请求里带组织和团队", func(t *testing.T) {
		var gotBody string
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			switch {
			case r.Method == http.MethodGet && r.URL.Path == "/skills":
				w.Write([]byte(`{"items":[],"has_more":false}`))
			case r.Method == http.MethodPost && r.URL.Path == "/skills":
				raw, _ := io.ReadAll(r.Body)
				gotBody = string(raw)
				w.Write([]byte(`{"id":"skill-1","slug":"demo","visibility":"team"}`))
			default:
				// 后面建版本、传制品那几步这轮用不上。
				w.WriteHeader(http.StatusInternalServerError)
			}
		}))
		defer srv.Close()

		// 建版本那几步会失败，这里不关心——要看的只是建 Skill 的请求体。
		_, _ = cmd.PublishOne(context.Background(), apiclient.New(srv.URL, "tok", 0),
			cmd.PublishTarget{Dir: demoSkillDir(t), RelPath: "demo"},
			cmd.PublishOptions{
				Slug:  "demo",
				Scope: cmd.PublishScope{OrganizationID: "org-1", TeamID: "team-1", Visibility: "team"},
			})
		for _, want := range []string{
			`"visibility":"team"`, `"organization_id":"org-1"`, `"owner_team_id":"team-1"`,
		} {
			if !strings.Contains(gotBody, want) {
				t.Fatalf("建 Skill 的请求体缺少 %s，实际 %s", want, gotBody)
			}
		}
	})

	t.Run("已有 Skill 时不假装改归属", func(t *testing.T) {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"items":[{"id":"skill-1","slug":"demo","visibility":"public"}],"has_more":false}`))
		}))
		defer srv.Close()

		_, err := cmd.PublishOne(context.Background(), apiclient.New(srv.URL, "tok", 0),
			cmd.PublishTarget{Dir: demoSkillDir(t), RelPath: "demo"},
			cmd.PublishOptions{
				Slug:  "demo",
				Scope: cmd.PublishScope{OrganizationID: "org-1", Visibility: "organization"},
			})
		if err == nil {
			t.Fatal("已有 Skill 改不了归属，应该报错")
		}
		if !strings.Contains(err.Error(), "已存在") {
			t.Fatalf("错误信息该说明 Skill 已存在: %v", err)
		}
	})

	// 「已提交审核」那条分支也得报出真实大小——漏填 SizeBytes 时输出恒为「0 字节」，
	// 用户会以为传了个空包上去。
	t.Run("待审的版本也报出制品大小", func(t *testing.T) {
		srv, putBytes := publishServer(t, "pending_review")

		result, err := cmd.PublishOne(context.Background(), apiclient.New(srv.URL, "tok", 0),
			cmd.PublishTarget{Dir: demoSkillDir(t), RelPath: "demo"},
			cmd.PublishOptions{
				Slug:  "demo",
				Scope: cmd.PublishScope{OrganizationID: "org-1", TeamID: "team-1", Visibility: "team"},
			})
		if err != nil {
			t.Fatalf("发布不该报错: %v", err)
		}
		if result.Action != "已提交审核" {
			t.Fatalf("团队可见的版本该说「已提交审核」，实际 %q", result.Action)
		}
		if result.SizeBytes <= 0 {
			t.Fatalf("「已提交审核」也得报出制品大小，实际 %d 字节", result.SizeBytes)
		}
		if result.SizeBytes != int64(*putBytes) {
			t.Fatalf("报的大小该等于实际传的字节数：报 %d，实际传 %d", result.SizeBytes, *putBytes)
		}
	})

	// 服务端扫描拦下的版本要说清楚：它没进审核队列，
	// 说成「已提交审核」会让人等一场不会来的审核。
	t.Run("版本被服务端拒了要报错", func(t *testing.T) {
		srv, _ := publishServer(t, "rejected")

		result, err := cmd.PublishOne(context.Background(), apiclient.New(srv.URL, "tok", 0),
			cmd.PublishTarget{Dir: demoSkillDir(t), RelPath: "demo"},
			cmd.PublishOptions{
				Slug:  "demo",
				Scope: cmd.PublishScope{OrganizationID: "org-1", TeamID: "team-1", Visibility: "team"},
			})
		if err == nil {
			t.Fatalf("被拒的版本不该报成功，实际 %q", result.Action)
		}
		if !strings.Contains(err.Error(), "rejected") {
			t.Fatalf("错误信息该说明版本被拒: %v", err)
		}
	})

	// 服务端把版本隔离时（内容里有真凭据），也要说清楚：
	// 它没进审核队列，说成「已提交审核」会让人等一场不会来的审核。
	t.Run("版本被服务端隔离要报错", func(t *testing.T) {
		srv, _ := publishServer(t, "quarantined")

		result, err := cmd.PublishOne(context.Background(), apiclient.New(srv.URL, "tok", 0),
			cmd.PublishTarget{Dir: demoSkillDir(t), RelPath: "demo"},
			cmd.PublishOptions{
				Slug:  "demo",
				Scope: cmd.PublishScope{OrganizationID: "org-1", TeamID: "team-1", Visibility: "team"},
			})
		if err == nil {
			t.Fatalf("被隔离的版本不该报成功，实际 %q", result.Action)
		}
		if !strings.Contains(err.Error(), "quarantined") {
			t.Fatalf("错误信息该说明版本被隔离: %v", err)
		}
	})

	// 服务端说 slug 被占用时，用户看到的应该是「slug 已被占用」，
	// 而不是「服务内部错误」。
	t.Run("slug被占用时报错说清楚", func(t *testing.T) {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/problem+json")
			if r.Method == http.MethodGet && r.URL.Path == "/skills" {
				w.Write([]byte(`{"items":[],"has_more":false}`))
				return
			}
			w.WriteHeader(http.StatusConflict)
			w.Write([]byte(`{"status":409,"code":"SLUG_TAKEN","detail":"slug 在该组织下已被占用: demo"}`))
		}))
		defer srv.Close()

		_, err := cmd.PublishOne(context.Background(), apiclient.New(srv.URL, "tok", 0),
			cmd.PublishTarget{Dir: demoSkillDir(t), RelPath: "demo"},
			cmd.PublishOptions{
				Slug:  "demo",
				Scope: cmd.PublishScope{OrganizationID: "org-1", OrganizationSlug: "acme", Visibility: "organization"},
			})
		if err == nil {
			t.Fatal("slug 被占用应该报错")
		}
		if !strings.Contains(err.Error(), "已被占用") {
			t.Fatalf("错误信息该说明 slug 已被占用: %v", err)
		}
	})
}

// publishServer 造一个能跑完「建 Skill → 建版本 → 传制品 → 收尾」的服务端。
// VersionStatus 是收尾时回给客户端的版本状态。
// 第二个返回值是实际收到的制品字节数。
func publishServer(t *testing.T, VersionStatus string) (*httptest.Server, *int) {
	t.Helper()
	putBytes := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/skills":
			w.Write([]byte(`{"items":[],"has_more":false}`))
		case r.Method == http.MethodGet && r.URL.Path == "/skills/skill-1/versions":
			w.Write([]byte(`{"items":[],"has_more":false}`))
		case r.Method == http.MethodPost && r.URL.Path == "/skills":
			w.Write([]byte(`{"id":"skill-1","slug":"demo","visibility":"team"}`))
		case r.Method == http.MethodPost && r.URL.Path == "/skills/skill-1/versions":
			w.Write([]byte(`{"version_id":"v-1","status":"draft","upload":{
				"session_id":"s-1","method":"PUT","url":"` + "http://" + r.Host + `/put","headers":{}}}`))
		case r.Method == http.MethodPut && r.URL.Path == "/put":
			raw, _ := io.ReadAll(r.Body)
			putBytes = len(raw)
			w.WriteHeader(http.StatusOK)
		case r.Method == http.MethodPost && r.URL.Path == "/uploads/s-1/complete":
			w.Write([]byte(`{"id":"s-1","status":"completed","version_status":"` + VersionStatus + `"}`))
		default:
			w.WriteHeader(http.StatusInternalServerError)
		}
	}))
	t.Cleanup(srv.Close)
	return srv, &putBytes
}

// LoadPublishScope 要向服务端问组织与团队，slug 解析错了用户只会看到一句空话。
func TestLoadPublishScope(t *testing.T) {
	t.Run("给组织和团队时解析出两个 id", func(t *testing.T) {
		var gotTeamsPath string
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			switch r.URL.Path {
			case "/me":
				json.NewEncoder(w).Encode(map[string]any{
					"organizations": []map[string]any{
						{"id": "org-1", "slug": "acme", "name": "Acme", "role": "owner"},
					},
				})
			case "/organizations/org-1/teams":
				gotTeamsPath = r.URL.Path
				json.NewEncoder(w).Encode([]map[string]any{
					{"id": "team-1", "organization_id": "org-1",
						"slug": "platform", "name": "Platform", "status": "active"},
				})
			default:
				w.WriteHeader(http.StatusNotFound)
			}
		}))
		defer srv.Close()

		scope, err := cmd.LoadPublishScope(context.Background(),
			apiclient.New(srv.URL, "tok", 0), "acme", "platform", "private")
		if err != nil {
			t.Fatalf("不该报错: %v", err)
		}
		if scope.Visibility != "team" || scope.OrganizationID != "org-1" || scope.TeamID != "team-1" {
			t.Fatalf("范围定错了: %+v", scope)
		}
		if gotTeamsPath != "/organizations/org-1/teams" {
			t.Fatalf("团队列表路径不对: %q", gotTeamsPath)
		}
	})

	t.Run("只给组织时不去拉团队列表", func(t *testing.T) {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path != "/me" {
				t.Errorf("不该请求 %s", r.URL.Path)
				w.WriteHeader(http.StatusInternalServerError)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			json.NewEncoder(w).Encode(map[string]any{
				"organizations": []map[string]any{
					{"id": "org-1", "slug": "acme", "name": "Acme", "role": "owner"},
				},
			})
		}))
		defer srv.Close()

		scope, err := cmd.LoadPublishScope(context.Background(),
			apiclient.New(srv.URL, "tok", 0), "acme", "", "private")
		if err != nil {
			t.Fatalf("不该报错: %v", err)
		}
		if scope.Visibility != "organization" || scope.OrganizationID != "org-1" {
			t.Fatalf("范围定错了: %+v", scope)
		}
	})

	t.Run("只给团队时直接报错，不去请求服务端", func(t *testing.T) {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			t.Errorf("不该请求服务端: %s", r.URL.Path)
			w.WriteHeader(http.StatusInternalServerError)
		}))
		defer srv.Close()

		_, err := cmd.LoadPublishScope(context.Background(),
			apiclient.New(srv.URL, "tok", 0), "", "platform", "private")
		if err == nil {
			t.Fatal("应该报错")
		}
		if !strings.Contains(err.Error(), "--org") {
			t.Fatalf("错误信息该说明要同时指定 --org: %v", err)
		}
	})
}
