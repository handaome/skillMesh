package cmd

import (
	"context"
	"fmt"
	"os"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/spf13/cobra"
)

// newGroupCmd 技能集合的命令组。
//
// 集合是「命名的一组 Skill」：团队把一套规范收成一个集合，新人一键装齐。
// 集合的成员是 Skill（可见性由每个 Skill 自己管），徽标只算「我装了几个」。
func newGroupCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "group",
		Short: "技能集合：命名的一组 Skill",
		Long: "集合把「哪些 Skill 该一起用」记下来。\n" +
			"建集合与改成员走 CLI，网页端看集合与一键装。",
	}
	cmd.AddCommand(newGroupListCmd(), newGroupShowCmd(), newGroupCreateCmd(), newGroupInstallCmd())
	return cmd
}

func newGroupListCmd() *cobra.Command {
	return supportsJSON(&cobra.Command{
		Use:   "list",
		Short: "列出我可见的集合",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			groups, err := client.Groups(cmd.Context())
			if err != nil {
				return err
			}
			if jsonEnabled() {
				return writeJSON(map[string]any{"items": groups})
			}
			if len(groups) == 0 {
				fmt.Println("还没有技能集合。用 skillmesh group create 建一个。")
				return nil
			}
			w := newTable(os.Stdout)
			fmt.Fprintln(w, "SLUG\t名称\t成员\t已装\t可见性")
			for _, group := range groups {
				fmt.Fprintf(w, "%s\t%s\t%d\t%d\t%s\n",
					group.Slug, group.Name, group.MemberCount, group.InstalledCount, group.Visibility)
			}
			return w.Flush()
		},
	})
}

func newGroupShowCmd() *cobra.Command {
	return supportsJSON(&cobra.Command{
		Use:   "show <slug>",
		Short: "看集合的成员与我装了几个",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			group, err := client.GroupBySlug(cmd.Context(), args[0])
			if err != nil {
				return err
			}
			if jsonEnabled() {
				return writeJSON(group)
			}
			fmt.Printf("%s (%s)\n%s\n\n", group.Name, group.Slug, group.Description)
			w := newTable(os.Stdout)
			fmt.Fprintln(w, "SKILL\t名称\t最新版本\t已装")
			for _, member := range group.Members {
				installed := "否"
				if member.Installed {
					installed = "是"
				}
				fmt.Fprintf(w, "%s\t%s\t%s\t%s\n",
					member.Slug, member.Name, orDash(member.LatestVersion), installed)
			}
			fmt.Printf("\n共 %d 个成员，已装 %d 个\n", group.MemberCount, group.InstalledCount)
			return w.Flush()
		},
	})
}

func newGroupCreateCmd() *cobra.Command {
	var name, description, visibility, organization, team string
	var skillRefs []string
	cmd := supportsJSON(&cobra.Command{
		Use:   "create <slug>",
		Short: "建一个技能集合",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			displayName := name
			if displayName == "" {
				displayName = args[0]
			}
			members := make([]string, 0, len(skillRefs))
			for _, ref := range skillRefs {
				id, err := resolveSkillID(cmd.Context(), client, ref)
				if err != nil {
					return err
				}
				members = append(members, id)
			}
			group, err := client.CreateGroup(cmd.Context(), apiclient.GroupCreate{
				Slug:           args[0],
				Name:           displayName,
				Description:    description,
				Visibility:     visibility,
				OrganizationID: organization,
				TeamID:         team,
				SkillIDs:       members,
			})
			if err != nil {
				return err
			}
			if jsonEnabled() {
				return writeJSON(group)
			}
			fmt.Printf("已建集合 %s（%s），成员 %d 个\n", group.Name, group.Slug, group.MemberCount)
			return nil
		},
	})
	cmd.Flags().StringVar(&name, "name", "", "集合名（不填就用 slug）")
	cmd.Flags().StringVar(&description, "description", "", "说明")
	cmd.Flags().StringVar(&visibility, "visibility", "private",
		"private / public / team / organization")
	cmd.Flags().StringVar(&organization, "org", "", "组织 id（team / organization 可见性时必填）")
	cmd.Flags().StringVar(&team, "team", "", "团队 id（team 可见性时必填）")
	cmd.Flags().StringSliceVar(&skillRefs, "skill", nil,
		"成员 Skill（slug 或 id），可重复指定")
	return cmd
}

func newGroupInstallCmd() *cobra.Command {
	var agentNames []string
	var scope string
	var replace, dryRun bool
	cmd := supportsJSON(&cobra.Command{
		Use:   "install <slug>",
		Short: "把集合里的 Skill 全装上",
		Long: "逐个装集合的成员。已装且没有本地改动的会跳过。\n" +
			"一个装不上不影响后面的——结果逐条报出来。",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			group, err := client.GroupBySlug(cmd.Context(), args[0])
			if err != nil {
				return err
			}
			projectRoot := projectRootDir()
			installed, failed := 0, 0
			for _, member := range group.Members {
				if err := installGroupMember(cmd.Context(), client, projectRoot, member, agentNames,
					scope, replace); err != nil {
					failed++
					notice("装 %s 失败：%s\n", member.Slug, err)
					continue
				}
				installed++
			}
			if jsonEnabled() {
				return writeJSON(map[string]any{"installed": installed, "failed": failed})
			}
			fmt.Printf("\n装好 %d 个，失败 %d 个\n", installed, failed)
			if failed > 0 {
				return fmt.Errorf("有 %d 个成员没装上", failed)
			}
			return nil
		},
	})
	cmd.Flags().StringSliceVar(&agentNames, "agent", []string{"claude-code"},
		"目标 Agent，可重复指定")
	cmd.Flags().StringVar(&scope, "scope", "user", "user 或 project")
	cmd.Flags().BoolVar(&replace, "replace", false, "覆盖不受 SkillMesh 管理的同名目录")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "只列出会装什么，不实际安装")
	return cmd
}

// installGroupMember 装集合里的一个成员。
//
// 逐个装而不是一把梭：一个装不上不该让后面全停。
func installGroupMember(ctx context.Context, client *apiclient.Client, projectRoot string,
	member apiclient.GroupMember, agentNames []string, scope string, replace bool) error {

	versions, err := client.Versions(ctx, member.ID)
	if err != nil {
		return err
	}
	latest := registryLatestVersion(ctx, client, member.ID)
	target, err := SelectVersion(versions, "", false, lockfile.LockedSkill{}, false, latest)
	if err != nil {
		return err
	}
	return installArtifact(ctx, client, projectRoot, installRequest{
		SkillID: member.ID,
		Version: target,
		Scope:   scope,
		Agents:  agentNames,
		Replace: replace,
	})
}

// resolveSkillID 把用户给的 Skill 引用（slug 或 id）解析成 id。
//
// 服务端只按 id 查，但用户手里通常是 slug——让他们先去搜 UUID 没有意义。
func resolveSkillID(ctx context.Context, client *apiclient.Client, ref string) (string, error) {
	page, err := client.Search(ctx, ref, "", "", false, 20, "")
	if err == nil {
		for _, skill := range page.Items {
			if skill.Slug == ref {
				return skill.ID, nil
			}
		}
	}
	// 不是 slug，就当 id 用：服务端会给出明确的「不存在或不可见」。
	return ref, nil
}
