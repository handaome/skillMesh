package cmd

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
		scope, err := resolvePublishScope(nil, teams, "", "public")
		if err != nil {
			t.Fatalf("不该报错: %v", err)
		}
		if scope.Visibility != "public" || scope.OrganizationID != "" {
			t.Fatalf("不该带组织信息: %+v", scope)
		}
	})

	t.Run("只给组织时可见性是 organization", func(t *testing.T) {
		scope, err := resolvePublishScope(&orgs[0], teams, "", "private")
		if err != nil {
			t.Fatalf("不该报错: %v", err)
		}
		if scope.Visibility != "organization" || scope.OrganizationID != "org-1" {
			t.Fatalf("可见性定错了: %+v", scope)
		}
	})

	t.Run("给了团队时可见性是 team 且带上两个 id", func(t *testing.T) {
		scope, err := resolvePublishScope(&orgs[0], teams, "platform", "private")
		if err != nil {
			t.Fatalf("不该报错: %v", err)
		}
		if scope.Visibility != "team" || scope.OrganizationID != "org-1" || scope.TeamID != "team-1" {
			t.Fatalf("可见性定错了: %+v", scope)
		}
	})

	t.Run("只给团队没给组织是错", func(t *testing.T) {
		if _, err := resolvePublishScope(nil, teams, "platform", "private"); err == nil {
			t.Fatal("应该报错")
		}
	})

	t.Run("组织不存在时报错并列出可选项", func(t *testing.T) {
		_, err := findOrganization(orgs, "nope")
		if err == nil {
			t.Fatal("应该报错")
		}
		if !strings.Contains(err.Error(), "acme") {
			t.Fatalf("错误信息里应列出可见组织: %v", err)
		}
	})

	t.Run("团队不存在时报错", func(t *testing.T) {
		if _, err := resolvePublishScope(&orgs[0], teams, "nope", "private"); err == nil {
			t.Fatal("应该报错")
		}
	})

	t.Run("角色不够发布时拒绝", func(t *testing.T) {
		if _, err := resolvePublishScope(&orgs[1], teams, "", "private"); err == nil {
			t.Fatal("member 不该能往组织发布")
		}
	})

	t.Run("非 active 的团队不能作为发布目标", func(t *testing.T) {
		archived := []apiclient.Team{
			{ID: "team-9", OrganizationID: "org-1", Slug: "old", Name: "Old", Status: "archived"},
		}
		_, err := resolvePublishScope(&orgs[0], archived, "old", "private")
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
		if got := roleAtLeast(c.actual, c.required); got != c.want {
			t.Errorf("roleAtLeast(%q, %q) = %v, 想要 %v", c.actual, c.required, got, c.want)
		}
	}
}

func TestCheckScopeFlags(t *testing.T) {
	if err := checkScopeFlags("", ""); err != nil {
		t.Fatalf("不该报错: %v", err)
	}
	if err := checkScopeFlags("acme", "platform"); err != nil {
		t.Fatalf("不该报错: %v", err)
	}
	if err := checkScopeFlags("", "platform"); err == nil {
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
		err := checkVisibilityFlag(c.orgSlug, c.teamSlug, c.requested)
		if (err != nil) != c.wantErr {
			t.Errorf("%s: err = %v, 想要报错 %v", c.name, err, c.wantErr)
		}
	}
}

// 已有 Skill 的归属与可见性改不动，带着新要求发布要报错而不是假装生效。
func TestCheckExistingSkill(t *testing.T) {
	skill := apiclient.Skill{Slug: "demo", Visibility: "public"}

	if err := checkExistingSkill(skill, publishScope{}, ""); err != nil {
		t.Fatalf("没提要求不该报错: %v", err)
	}
	if err := checkExistingSkill(skill, publishScope{}, "public"); err != nil {
		t.Fatalf("显式要的就是现值，不该报错: %v", err)
	}
	if err := checkExistingSkill(skill, publishScope{}, "private"); err == nil {
		t.Fatal("可见性改不了，应该报错")
	}
	if err := checkExistingSkill(skill, publishScope{OrganizationID: "org-1"}, ""); err == nil {
		t.Fatal("已有 Skill 不能改归属，应该报错")
	}
	if err := checkExistingSkill(skill, publishScope{TeamID: "team-1"}, ""); err == nil {
		t.Fatal("已有 Skill 不能改归属，应该报错")
	}
}

// sync 这类内部调用不给 Scope，可见性得回落到 options.Visibility。
func TestPublishOptions可见性回落(t *testing.T) {
	if got := (publishOptions{Visibility: "private"}).visibilityFor(); got != "private" {
		t.Fatalf("没有 Scope 时该用原可见性，得到 %q", got)
	}
	options := publishOptions{Visibility: "private", Scope: publishScope{Visibility: "team"}}
	if got := options.visibilityFor(); got != "team" {
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

// publishOne 的两条分支：新建时把归属带进请求；已有 Skill 时不假装能改归属。
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
		_, _ = publishOne(context.Background(), apiclient.New(srv.URL, "tok", 0),
			publishTarget{Dir: demoSkillDir(t), RelPath: "demo"},
			publishOptions{
				Slug:  "demo",
				Scope: publishScope{OrganizationID: "org-1", TeamID: "team-1", Visibility: "team"},
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

		_, err := publishOne(context.Background(), apiclient.New(srv.URL, "tok", 0),
			publishTarget{Dir: demoSkillDir(t), RelPath: "demo"},
			publishOptions{
				Slug:  "demo",
				Scope: publishScope{OrganizationID: "org-1", Visibility: "organization"},
			})
		if err == nil {
			t.Fatal("已有 Skill 改不了归属，应该报错")
		}
		if !strings.Contains(err.Error(), "已存在") {
			t.Fatalf("错误信息该说明 Skill 已存在: %v", err)
		}
	})
}

// loadPublishScope 要向服务端问组织与团队，slug 解析错了用户只会看到一句空话。
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

		scope, err := loadPublishScope(context.Background(),
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

		scope, err := loadPublishScope(context.Background(),
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

		_, err := loadPublishScope(context.Background(),
			apiclient.New(srv.URL, "tok", 0), "", "platform", "private")
		if err == nil {
			t.Fatal("应该报错")
		}
		if !strings.Contains(err.Error(), "--org") {
			t.Fatalf("错误信息该说明要同时指定 --org: %v", err)
		}
	})
}
