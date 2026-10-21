package cmd

import (
	"context"
	"fmt"
	"os"
	"text/tabwriter"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/spf13/cobra"
)

// restorePageSize 是一次拉取的条数。个人空间不会有几千个 Skill，
// 200 条足够一次拉完；真超了会提示还有更多。
const restorePageSize = 200

func newRestoreCmd() *cobra.Command {
	var agentNames []string
	var scope string
	var dryRun bool

	cmd := &cobra.Command{
		Use:   "restore [slug...]",
		Short: "把个人私有空间里的 Skill 装回本机",
		Long: "换设备后一键恢复：拉取我名下的 Skill，装上最新已发布版本。\n" +
			"不给 slug 就是全部装。\n" +
			"只恢复「我的」，别人的 public Skill 用 search 单独装。",
		Args: cobra.ArbitraryArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			skills, err := mySkills(cmd.Context(), client)
			if err != nil {
				return err
			}
			wanted := pickBySlug(skills, args)
			if len(wanted) == 0 {
				fmt.Println("个人空间里没有可恢复的 Skill（要先 publish/sync 过）。")
				return nil
			}
			projectRoot := mustGetwd()
			results := restoreAll(cmd.Context(), client, projectRoot, wanted, agentNames, scope, dryRun)
			return reportRestore(results)
		},
	}
	cmd.Flags().StringSliceVar(&agentNames, "agent", []string{"claude-code"}, "目标 Agent，可重复指定")
	cmd.Flags().StringVar(&scope, "scope", "user", "user 或 project")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "只列出会装什么，不落盘")
	return cmd
}

// restoreResult 是单个 Skill 的恢复结果。
type restoreResult struct {
	Name  string
	State string
	Why   string
}

// mySkills 拉取我名下的全部 Skill，翻完所有分页。
func mySkills(ctx context.Context, client *apiclient.Client) ([]apiclient.Skill, error) {
	var all []apiclient.Skill
	cursor := ""
	for {
		page, err := client.Search(ctx, "", "", "", true, restorePageSize, cursor)
		if err != nil {
			return nil, err
		}
		all = append(all, page.Items...)
		if !page.HasMore || page.NextCursor == nil {
			return all, nil
		}
		cursor = *page.NextCursor
	}
}

// pickBySlug 按名字挑；不给名字就是全要。
func pickBySlug(skills []apiclient.Skill, slugs []string) []apiclient.Skill {
	if len(slugs) == 0 {
		return skills
	}
	wanted := make(map[string]bool, len(slugs))
	for _, slug := range slugs {
		wanted[slug] = true
	}
	picked := make([]apiclient.Skill, 0, len(slugs))
	for _, skill := range skills {
		if wanted[skill.Slug] {
			picked = append(picked, skill)
			delete(wanted, skill.Slug)
		}
	}
	for _, slug := range slugs {
		if wanted[slug] {
			fmt.Fprintf(os.Stderr, "个人空间里没有 %s，跳过\n", slug)
		}
	}
	return picked
}

// restoreAll 逐个恢复：一个失败不该挡住其他全部。
func restoreAll(ctx context.Context, client *apiclient.Client, projectRoot string,
	skills []apiclient.Skill, agentNames []string, scope string, dryRun bool) []restoreResult {

	results := make([]restoreResult, 0, len(skills))
	for _, skill := range skills {
		if dryRun {
			results = append(results, restoreResult{Name: skill.Slug, State: "将恢复"})
			continue
		}
		state, why, err := restoreOne(ctx, client, projectRoot, skill, agentNames, scope)
		if err != nil {
			results = append(results, restoreResult{Name: skill.Slug, State: "失败", Why: err.Error()})
			continue
		}
		results = append(results, restoreResult{Name: skill.Slug, State: state, Why: why})
	}
	return results
}

func restoreOne(ctx context.Context, client *apiclient.Client, projectRoot string,
	skill apiclient.Skill, agentNames []string, scope string) (string, string, error) {

	versions, err := client.Versions(ctx, skill.ID)
	if err != nil {
		return "", "", err
	}
	target, err := pickVersion(versions, "", false, lockfile.LockedSkill{}, false)
	if err != nil {
		// 一个还没发过版的 Skill 不该让整批恢复失败。
		return "跳过", err.Error(), nil
	}
	if err := installArtifact(ctx, client, projectRoot, installRequest{
		SkillID: skill.ID,
		Version: target,
		Agents:  agentNames,
		Scope:   scope,
	}); err != nil {
		return "", "", err
	}
	return "已恢复", target.Version, nil
}

func reportRestore(results []restoreResult) error {
	w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(w, "SKILL\t结果\t说明")
	counts := map[string]int{}
	for _, result := range results {
		counts[result.State]++
		fmt.Fprintf(w, "%s\t%s\t%s\n", result.Name, result.State, result.Why)
	}
	if err := w.Flush(); err != nil {
		return err
	}
	failed := counts["失败"]
	fmt.Printf("\n已恢复 %d，跳过 %d，失败 %d\n", counts["已恢复"], counts["跳过"], failed)
	if failed > 0 {
		return fmt.Errorf("%d 个恢复失败", failed)
	}
	return nil
}
