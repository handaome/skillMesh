package cmd

import (
	"fmt"

	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/spf13/cobra"
)

func newRollbackCmd() *cobra.Command {
	var to string
	cmd := &cobra.Command{
		Use:   "rollback <skill-name>",
		Short: "把已安装的 Skill 回滚到指定版本",
		Long: "版本制品不可变，所以回滚就是再装一次更早的版本，不存在「改回去」这种事。\n" +
			"只支持注册中心来源（skillmesh install 装的）。\n" +
			"Git 来源请用 update 配合固定的 commit 或 tag。",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if to == "" {
				return fmt.Errorf("必须用 --to 指定回滚到的版本")
			}
			projectRoot := mustGetwd()
			lock, err := lockfile.Load(projectRoot)
			if err != nil {
				return err
			}
			entry, ok := lock.Find(args[0])
			if !ok {
				return fmt.Errorf("锁文件中没有 %s", args[0])
			}
			if entry.SourceID == "" {
				return fmt.Errorf("%s 来自 Git 来源，回滚请改用 update（配合固定 commit 或 tag）", entry.Name)
			}
			if len(entry.Agents) == 0 {
				return fmt.Errorf("%s 没有登记 Agent 目录，先 skillmesh install 一次", entry.Name)
			}

			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			versions, err := client.Versions(cmd.Context(), entry.SourceID)
			if err != nil {
				return err
			}
			target, err := PickVersion(versions, to, false, entry, false)
			if err != nil {
				return err
			}
			fmt.Printf("把 %s 从 %s 回滚到 %s\n", entry.Name, orDash(entry.Version), target.Version)
			return installArtifact(cmd.Context(), client, projectRoot, installRequest{
				SkillID: entry.SourceID,
				Version: target,
				Agents:  entry.Agents,
				Scope:   scopeOf(entry.Scope, projectRoot),
			})
		},
	}
	cmd.Flags().StringVar(&to, "to", "", "回滚到的版本号")
	return cmd
}
