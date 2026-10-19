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

// agentDirFor 解析某个 Agent 在当前项目下的安装目录。
// 有 skillmesh.yaml 视为项目根，用 project 作用域；否则用 user 作用域。
func agentDirFor(name, projectRoot string) (string, error) {
	scope := "user"
	if _, err := os.Stat(filepath.Join(projectRoot, lockfile.ManifestName)); err == nil {
		scope = "project"
	}
	return agent.Dir(cfg, name, scope, projectRoot)
}

func newListCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "列出本项目已登记的安装",
		Args:  cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			projectRoot := mustGetwd()
			lock, err := lockfile.Load(projectRoot)
			if err != nil {
				return err
			}
			if len(lock.Skills) == 0 {
				fmt.Println("尚未登记任何 Skill")
				return nil
			}
			w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
			fmt.Fprintln(w, "SKILL\tAGENT\tCOMMIT\t路径\t受管")
			for _, skill := range lock.Skills {
				for _, agentName := range skill.Agents {
					base, err := agentDirFor(agentName, projectRoot)
					if err != nil {
						base = "?"
					}
					target := filepath.Join(base, skill.Name)
					managed := "否"
					if installer.IsManaged(target) {
						managed = "是"
					}
					fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\n",
						skill.Name, agentName, short(skill.ResolvedCommit), target, managed)
				}
			}
			return w.Flush()
		},
	}
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
				base, err := agentDirFor(agentName, projectRoot)
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
