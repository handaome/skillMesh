package cmd

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
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
		{ID: "team-1", OrganizationID: "org-1", Slug: "platform", Name: "Platform"},
	}

	t.Run("不指定组织时只用原可见性", func(t *testing.T) {
		scope, err := resolvePublishScope(orgs, teams, "", "", "public")
		if err != nil {
			t.Fatalf("不该报错: %v", err)
		}
		if scope.Visibility != "public" || scope.OrganizationID != "" {
			t.Fatalf("不该带组织信息: %+v", scope)
		}
	})

	t.Run("只给组织时可见性是 organization", func(t *testing.T) {
		scope, err := resolvePublishScope(orgs, teams, "acme", "", "private")
		if err != nil {
			t.Fatalf("不该报错: %v", err)
		}
		if scope.Visibility != "organization" || scope.OrganizationID != "org-1" {
			t.Fatalf("可见性定错了: %+v", scope)
		}
	})

	t.Run("给了团队时可见性是 team 且带上两个 id", func(t *testing.T) {
		scope, err := resolvePublishScope(orgs, teams, "acme", "platform", "private")
		if err != nil {
			t.Fatalf("不该报错: %v", err)
		}
		if scope.Visibility != "team" || scope.OrganizationID != "org-1" || scope.TeamID != "team-1" {
			t.Fatalf("可见性定错了: %+v", scope)
		}
	})

	t.Run("只给团队没给组织是错", func(t *testing.T) {
		if _, err := resolvePublishScope(orgs, teams, "", "platform", "private"); err == nil {
			t.Fatal("应该报错")
		}
	})

	t.Run("组织不存在时报错并列出可选项", func(t *testing.T) {
		_, err := resolvePublishScope(orgs, teams, "nope", "", "private")
		if err == nil {
			t.Fatal("应该报错")
		}
		if !strings.Contains(err.Error(), "acme") {
			t.Fatalf("错误信息里应列出可见组织: %v", err)
		}
	})

	t.Run("团队不存在时报错", func(t *testing.T) {
		if _, err := resolvePublishScope(orgs, teams, "acme", "nope", "private"); err == nil {
			t.Fatal("应该报错")
		}
	})

	t.Run("角色不够发布时拒绝", func(t *testing.T) {
		if _, err := resolvePublishScope(orgs, teams, "other", "", "private"); err == nil {
			t.Fatal("member 不该能往组织发布")
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
