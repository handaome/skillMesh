package cmd

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
)

// errTeamNeedsOrg 是「给了 --team 没给 --org」的错误。
var errTeamNeedsOrg = errors.New("指定 --team 时必须同时指定 --org")

// publishScope 是发布到组织/团队时要带的 id 与可见性。
// slug 一起带着：报错时要说的是「组织 acme」而不是一串 UUID。
type publishScope struct {
	OrganizationID   string
	OrganizationSlug string
	TeamID           string
	TeamSlug         string
	Visibility       string
}

// findOrganization 按 slug 找组织。
func findOrganization(orgs []apiclient.OrgMembership, slug string) (*apiclient.OrgMembership, error) {
	for i := range orgs {
		if orgs[i].Slug == slug {
			return &orgs[i], nil
		}
	}
	slugs := make([]string, 0, len(orgs))
	for _, org := range orgs {
		slugs = append(slugs, org.Slug)
	}
	return nil, fmt.Errorf("组织 %q 不存在或你不是成员，可选：%s",
		slug, strings.Join(slugs, " / "))
}

// findTeam 按 slug 找团队。列表是该组织的，所以不在里面只说明「不在这个组织下」。
func findTeam(teams []apiclient.Team, orgSlug, slug string) (*apiclient.Team, error) {
	for i := range teams {
		if teams[i].Slug == slug {
			return &teams[i], nil
		}
	}
	slugs := make([]string, 0, len(teams))
	for _, team := range teams {
		slugs = append(slugs, team.Slug)
	}
	return nil, fmt.Errorf("团队 %q 不在组织 %q 下，可选：%s",
		slug, orgSlug, strings.Join(slugs, " / "))
}

// checkScopeFlags 校验 --org / --team 的组合：给了团队就必须同时给组织。
func checkScopeFlags(orgSlug, teamSlug string) error {
	if orgSlug == "" && teamSlug != "" {
		return errTeamNeedsOrg
	}
	return nil
}

// checkVisibilityFlag 校验显式给的 --visibility 与 --org/--team 是否自洽。
// requested 是用户显式给的 --visibility，没给就是空串（默认值不算）。
func checkVisibilityFlag(orgSlug, teamSlug, requested string) error {
	if requested == "" {
		return nil
	}
	if orgSlug == "" {
		if requested == "team" || requested == "organization" {
			return fmt.Errorf("可见性 %q 需要配合 --org（团队可见还要 --team）", requested)
		}
		return nil
	}
	if requested == "team" && teamSlug == "" {
		return errors.New("可见性 team 需要同时指定 --team")
	}
	want := "organization"
	if teamSlug != "" {
		want = "team"
	}
	if requested != want {
		return fmt.Errorf("可见性 %q 与 --org/--team 冲突：指定了归属，可见性只能是 %q", requested, want)
	}
	return nil
}

// resolvePublishScope 定出发布范围：org 已经查好，teams 是该组织的团队列表。
//
// 指定团队 → visibility=team（组织与团队 id 都要带）；
// 只指定组织 → visibility=organization；都不指定 → 用原可见性。
func resolvePublishScope(org *apiclient.OrgMembership, teams []apiclient.Team,
	teamSlug, visibility string) (publishScope, error) {

	if org == nil {
		if teamSlug != "" {
			return publishScope{}, errTeamNeedsOrg
		}
		return publishScope{Visibility: visibility}, nil
	}
	if !roleAtLeast(org.Role, "publisher") {
		return publishScope{}, fmt.Errorf(
			"你在组织 %q 的角色是 %s，发布到组织/团队需要 publisher 及以上", org.Slug, org.Role)
	}
	scope := publishScope{OrganizationID: org.ID, OrganizationSlug: org.Slug, Visibility: "organization"}
	if teamSlug == "" {
		return scope, nil
	}
	team, err := findTeam(teams, org.Slug, teamSlug)
	if err != nil {
		return publishScope{}, err
	}
	if team.Status != "active" {
		return publishScope{}, fmt.Errorf("团队 %q 不是 active 状态（当前 %q），不能作为发布目标",
			teamSlug, team.Status)
	}
	scope.TeamID = team.ID
	scope.TeamSlug = team.Slug
	scope.Visibility = "team"
	return scope, nil
}

// loadPublishScope 取我可见的组织与团队，再解析成发布范围。
func loadPublishScope(ctx context.Context, client *apiclient.Client,
	orgSlug, teamSlug, visibility string) (publishScope, error) {

	if err := checkScopeFlags(orgSlug, teamSlug); err != nil {
		return publishScope{}, err
	}
	if orgSlug == "" {
		return publishScope{Visibility: visibility}, nil
	}
	me, err := client.Me(ctx)
	if err != nil {
		return publishScope{}, err
	}
	org, err := findOrganization(me.Organizations, orgSlug)
	if err != nil {
		return publishScope{}, err
	}
	var teams []apiclient.Team
	if teamSlug != "" {
		// 只有发到团队才需要团队列表：组织可见不用多打这一趟，
		// 团队接口挂了也不该把「发到组织」一起拖下水。
		teams, err = client.Teams(ctx, org.ID)
		if err != nil {
			return publishScope{}, err
		}
	}
	return resolvePublishScope(org, teams, teamSlug, visibility)
}

// checkExistingSkill 已有 Skill 的归属与可见性都改不动——
// 服务端 PATCH /skills/{id} 只收名称、描述、标签，slug 与可见性不收。
// 带着新要求去发布只会让人以为生效了，直接说清楚。
//
// 但「重申同一个归属」不是改归属：发 v2 时再带上一样的 --org/--team 是主流程，
// 只有归属对不上才拦。不带 scope 时一律放行——那是「沿用已登记的归属」。
func checkExistingSkill(skill apiclient.Skill, scope publishScope, requested string) error {
	if scope.OrganizationID != "" || scope.TeamID != "" {
		actual := publishScope{OrganizationID: skill.OrganizationID, TeamID: skill.OwnerTeamID}
		if actual.OrganizationID != scope.OrganizationID || actual.TeamID != scope.TeamID {
			return fmt.Errorf(
				"Skill %q 已存在，归属改不了：它已登记在 %s，你要发到 %s。去掉 --org/--team 会沿用它已登记的归属",
				skill.Slug, actual.describe(), scope.describe())
		}
	}
	if requested != "" && requested != skill.Visibility {
		return fmt.Errorf("Skill %q 已存在，可见性改不了（现在是 %s）", skill.Slug, skill.Visibility)
	}
	return nil
}

// describe 说清一段归属：组织与团队（或个人）。没有 slug 就退回 id。
func (s publishScope) describe() string {
	org := s.OrganizationSlug
	if org == "" {
		org = s.OrganizationID
	}
	team := s.TeamSlug
	if team == "" {
		team = s.TeamID
	}
	switch {
	case org == "" && team == "":
		return "个人（不属于任何组织或团队）"
	case team == "":
		return "组织 " + org
	default:
		return "组织 " + org + " 的团队 " + team
	}
}

// optionalString 空串转 nil——omitempty 才知道不传。
func optionalString(value string) *string {
	if value == "" {
		return nil
	}
	return &value
}
