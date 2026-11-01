package cmd

import (
	"context"
	"fmt"
	"os"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/spf13/cobra"
)

func newSearchCmd() *cobra.Command {
	var visibility, tag, source, org, team, cursor string
	var limit int
	var mine bool
	cmd := supportsJSON(&cobra.Command{
		Use:   "search [关键字]",
		Short: "搜索可见的 Skill",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if source != "" && !contains(searchSources, source) {
				return fmt.Errorf("--source 只能是 %s，收到 %q",
					strings.Join(searchSources, " / "), source)
			}
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			keyword := ""
			if len(args) == 1 {
				keyword = args[0]
			}
			page, err := client.Search(cmd.Context(), keyword, visibility, tag, mine, limit, cursor)
			if err != nil {
				return err
			}
			// 来源、组织与团队都是拿回来自己筛的：服务端的搜索接口
			// 不接这几个参数，但结果里带着够用的字段（来源摘要与归属 ID）。
			scope, err := resolveScope(cmd.Context(), client, org, team)
			if err != nil {
				return err
			}
			items := filterSkills(page.Items, source, scope)
			if jsonEnabled() {
				return writeJSON(map[string]any{
					"items":    jsonItems(items),
					"has_more": page.HasMore,
				})
			}
			return printSearchTable(items, page.HasMore)
		},
	})
	cmd.Flags().StringVar(&visibility, "visibility", "", "按可见性过滤：private/team/organization/public")
	cmd.Flags().StringVar(&tag, "tag", "", "按标签过滤")
	cmd.Flags().StringVar(&source, "source", "", "按来源过滤：git / cloud / local")
	cmd.Flags().StringVar(&org, "org", "", "只看某个组织下的 Skill（slug）")
	cmd.Flags().StringVar(&team, "team", "", "只看某个团队下的 Skill（slug）")
	cmd.Flags().StringVar(&cursor, "cursor", "", "翻页游标：上一页返回的 next_cursor")
	cmd.Flags().IntVar(&limit, "limit", 20, "返回条数")
	cmd.Flags().BoolVar(&mine, "mine", false, "只搜我名下的 Skill（个人私有空间）")
	return cmd
}

// searchSources 是 --source 允许的取值。
var searchSources = []string{"git", "cloud", "local"}

// scopeFilter 是按组织与团队过滤的条件（服务端给的是 ID）。
type scopeFilter struct {
	orgID  string
	teamID string
}

// resolveScope 把组织与团队的 slug 换成服务端用的 ID。
//
// 服务端的搜索接口不接组织与团队参数，只能拿回来自己筛；
// 而结果里给的是 ID，用户手里通常是 slug——先换。
//
// 换不出来就报错：悄悄筛成空集，用户只会以为「这个组织下没有 Skill」。
func resolveScope(ctx context.Context, client *apiclient.Client, org, team string) (scopeFilter, error) {
	filter := scopeFilter{}
	if org == "" && team == "" {
		return filter, nil
	}
	me, err := client.Me(ctx)
	if err != nil {
		return filter, fmt.Errorf("取当前账号失败: %w", err)
	}
	if org != "" {
		membership, err := FindOrganization(me.Organizations, org)
		if err != nil {
			return filter, err
		}
		filter.orgID = membership.ID
	}
	if team == "" {
		return filter, nil
	}
	// 团队列表按组织取：--team 没给 --org 时就把我加入的组织都看一遍。
	teams := []apiclient.Team{}
	for _, membership := range me.Organizations {
		list, err := client.Teams(ctx, membership.ID)
		if err != nil {
			continue // 不是这个组织的成员就看不到它的团队
		}
		teams = append(teams, list...)
	}
	found, err := findTeam(teams, orDash(org), team)
	if err != nil {
		return filter, err
	}
	filter.teamID = found.ID
	if filter.orgID == "" {
		filter.orgID = found.OrganizationID
	}
	return filter, nil
}

// filterSkills 按来源、组织与团队筛掉不匹配的 Skill。
func filterSkills(items []apiclient.Skill, source string, scope scopeFilter) []apiclient.Skill {
	kept := make([]apiclient.Skill, 0, len(items))
	for _, skill := range items {
		if !matchesSource(skill, source) {
			continue
		}
		if scope.teamID != "" && skill.OwnerTeamID != scope.teamID {
			continue
		}
		if scope.orgID != "" && skill.OrganizationID != scope.orgID {
			continue
		}
		kept = append(kept, skill)
	}
	return kept
}

// matchesSource 判断 Skill 的来源类型是否符合 --source。
//
// 服务端的搜索接口没有来源过滤参数，但结果里带着来源摘要，
// 拿到手自己筛就行。
//
// 服务端的来源取值是 local_import / git / registry / archive：
// 本地导入的两种（local_import 与 archive）都算「本地来源」，
// 其余算云端来源。
func matchesSource(skill apiclient.Skill, want string) bool {
	if want == "" {
		return true
	}
	kind := "cloud"
	if skill.Source != nil && skill.Source.SourceType != "" {
		kind = skill.Source.SourceType
	}
	switch want {
	case "git":
		return kind == "git"
	case "local":
		return kind == "local_import" || kind == "archive"
	default: // cloud：Git 与本地导入之外的都算云端来源
		return kind != "git" && kind != "local_import" && kind != "archive"
	}
}

// printSearchTable 打印人读表格。
func printSearchTable(items []apiclient.Skill, hasMore bool) error {
	if len(items) == 0 {
		fmt.Println("没有匹配的 Skill")
		return nil
	}
	w := newTable(os.Stdout)
	fmt.Fprintln(w, "SLUG\t名称\t可见性\t最新版本\tID")
	for _, skill := range items {
		latest := "-"
		if skill.LatestVersion != nil {
			latest = *skill.LatestVersion
		}
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\n",
			skill.Slug, skill.Name, skill.Visibility, latest, skill.ID)
	}
	if err := w.Flush(); err != nil {
		return err
	}
	if hasMore {
		fmt.Println("还有更多结果：用 --cursor 翻页，或缩小关键字范围。")
	}
	return nil
}
