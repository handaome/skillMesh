package cmd

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"text/tabwriter"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/installer"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/spf13/cobra"
)

// scopeOf 决定这次操作的作用域。锁文件里记了 scope 就以记录为准——
// add 装到项目级、update 却写回用户目录，是很难查的一类错（设计文档 §8）。
// 老锁文件没有这个字段，按有没有 skillmesh.yaml 兜底。
func scopeOf(scope, projectRoot string) string {
	if scope != "" {
		return scope
	}
	if _, err := os.Stat(filepath.Join(projectRoot, lockfile.ManifestName)); err == nil {
		return "project"
	}
	return "user"
}

// agentDirFor 解析某个 Agent 在当前项目下的安装目录。
func agentDirFor(name string, entry lockfile.LockedSkill, projectRoot string) (string, error) {
	return agent.Dir(cfg, name, scopeOf(entry.Scope, projectRoot), projectRoot)
}

func newListCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "列出本项目已登记的本地安装",
		Long: "列出本项目锁文件里登记的本地安装。\n" +
			"注意这是「本地装了什么」，不是「云端有什么」——\n" +
			"发布到云端的 Skill 用 skillmesh search 查。",
		Args: cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			projectRoot := mustGetwd()
			lock, err := lockfile.Load(projectRoot)
			if err != nil {
				return err
			}
			if len(lock.Skills) == 0 {
				fmt.Println("本项目尚未登记任何本地安装。")
				fmt.Println("已发布到云端的 Skill 用 skillmesh search 查。")
				return nil
			}
			w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
			fmt.Fprintln(w, "SKILL\tAGENT\t版本/commit\t路径\t受管")
			for _, skill := range lock.Skills {
				for _, agentName := range skill.Agents {
					base, err := agentDirFor(agentName, skill, projectRoot)
					if err != nil {
						base = "?"
					}
					target := filepath.Join(base, skill.Name)
					managed := "否"
					if installer.IsManaged(target) {
						managed = "是"
					}
					fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\n",
						skill.Name, agentName, resolvedLabel(skill), target, managed)
				}
			}
			return w.Flush()
		},
	}
}

// resolvedLabel 显示锁住的东西：Git 来源锁的是 commit，注册中心来源锁的是版本号。
func resolvedLabel(skill lockfile.LockedSkill) string {
	if skill.ResolvedCommit != "" {
		return short(skill.ResolvedCommit)
	}
	return orDash(skill.Version)
}

func newStatusCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "status [skill-name]",
		Short: "查看锁文件里的安装状态（JSON）",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			lock, err := lockfile.Load(mustGetwd())
			if err != nil {
				return err
			}
			var payload any = map[string]any{
				"resolved_at": lock.ResolvedAt,
				"skills":      lock.Skills,
			}
			if len(args) == 1 {
				skill, ok := lock.Find(args[0])
				if !ok {
					return fmt.Errorf("锁文件中没有 %s", args[0])
				}
				payload = skill
			}
			encoder := json.NewEncoder(os.Stdout)
			encoder.SetIndent("", "  ")
			return encoder.Encode(payload)
		},
	}
}

func newUninstallCmd() *cobra.Command {
	var force bool
	cmd := &cobra.Command{
		Use:   "uninstall <skill-name>",
		Short: "移除安装内容与锁文件记录",
		Args:  cobra.ExactArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			projectRoot := mustGetwd()
			lock, err := lockfile.Load(projectRoot)
			if err != nil {
				return err
			}
			entry, ok := lock.Find(args[0])
			if !ok {
				return fmt.Errorf("锁文件中没有 %s", args[0])
			}
			for _, agentName := range entry.Agents {
				base, err := agentDirFor(agentName, entry, projectRoot)
				if err != nil {
					return err
				}
				target := filepath.Join(base, entry.Name)
				if force {
					if err := os.RemoveAll(target); err != nil {
						return err
					}
				} else if err := installer.RemoveUnmanaged(target); err != nil {
					return err
				}
				fmt.Printf("已移除 %s\n", target)
			}
			lock.Remove(entry.Name)
			return lock.Save(projectRoot)
		},
	}
	cmd.Flags().BoolVar(&force, "force", false, "连同不受 SkillMesh 管理的目录一起删除")
	return cmd
}
