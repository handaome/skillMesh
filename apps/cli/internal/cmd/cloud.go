package cmd

import (
	"fmt"
	"github.com/handaome/skillmesh/apps/cli/internal/oplog"
	"github.com/spf13/cobra"
	"os"
)

// newCloudCmd 云端 Skill 的删除与还原。
//
// 删除是软删除：数据留着，回收期内能还原。客户端再同步时，
// 会把本地那份一起删掉（移到本机回收站，仍可找回）。
func newCloudCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "cloud",
		Short: "云端 Skill 的删除与还原",
		Long: "删掉云端的 Skill 是软删除：版本、制品都留着，回收期内能还原。\n" +
			"还原之后各端再同步，云端被删的那份在本地也会一起删掉。",
		Args: cobra.NoArgs,
	}
	cmd.AddCommand(newCloudDeleteCmd(), newCloudRestoreCmd(), newCloudDeletedCmd(),
		newRecycleCmd())
	return cmd
}

func newCloudDeleteCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "delete <slug>",
		Short: "删除云端的 Skill（软删除，可还原）",
		Long: "把云端的 Skill 归档掉：它不再出现在列表里，也不再能被安装。\n" +
			"版本与制品都留着，skillmesh cloud restore 可以找回来。\n" +
			"各端再跑一次 sync，本地那份会被一起删掉（进本机回收站）。",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			id, err := ResolveSkillID(cmd.Context(), client, args[0])
			if err != nil {
				return err
			}
			if err := client.ArchiveSkill(cmd.Context(), id); err != nil {
				opLog().RecordFailure(oplog.ActionCloudDelete, args[0], err.Error())
				return err
			}
			fmt.Printf("已删除 %s。找回来：skillmesh cloud restore %s\n", args[0], args[0])
			opLog().Record(oplog.ActionCloudDelete, args[0], "云端已归档",
				map[string]string{"skill_id": id})
			return nil
		},
	}
}

func newCloudRestoreCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "restore <slug>",
		Short: "还原被删除的 Skill",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			deleted, err := client.DeletedSkills(cmd.Context())
			if err != nil {
				return err
			}
			for _, item := range deleted {
				if item.Slug != args[0] {
					continue
				}
				skill, err := client.RestoreSkill(cmd.Context(), item.ID)
				if err != nil {
					opLog().RecordFailure(oplog.ActionCloudRestore, args[0], err.Error())
					return err
				}
				fmt.Printf("已还原 %s（%s）\n", skill.Slug, skill.ID)
				opLog().Record(oplog.ActionCloudRestore, skill.Slug, "云端已还原",
					map[string]string{"skill_id": skill.ID})
				return nil
			}
			return fmt.Errorf("没有找到被删除的 %s，用 skillmesh cloud deleted 看清单", args[0])
		},
	}
}

func newCloudDeletedCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "deleted",
		Short: "看自己删掉过哪些 Skill",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			deleted, err := client.DeletedSkills(cmd.Context())
			if err != nil {
				return err
			}
			if len(deleted) == 0 {
				fmt.Println("没有删过东西。")
				return nil
			}
			w := newTable(os.Stdout)
			fmt.Fprintln(w, "SLUG\t名称\tID\t删除时间")
			for _, item := range deleted {
				fmt.Fprintf(w, "%s\t%s\t%s\t%s\n", item.Slug, item.Name, item.ID, item.DeletedAt)
			}
			return w.Flush()
		},
	}
}
