package cmd

import (
	"fmt"

	"github.com/handaome/skillmesh/apps/cli/internal/oplog"
	"github.com/spf13/cobra"
)

// newCloudRevokeCmd 撤回云端的某个版本。
//
// 撤回跟删整个 Skill 不是一回事：Skill 还在，只是这一个版本不再对外可用。
// 用在「制品发错了」这种场景——错的版本不能继续让人装，但已经装好的
// 内容不能无声消失（设计文档 §10 撤回）。
func newCloudRevokeCmd() *cobra.Command {
	var reason string
	cmd := &cobra.Command{
		Use:   "revoke <slug> <version>",
		Short: "撤回云端的某个版本",
		Long: "把云端的某个版本置为撤回状态：装不上了，也不能再被送审或转正。\n" +
			"撤回不可逆——要恢复只能发一个新版本。制品与文件仍留在云端，只是不再对外可用。\n" +
			"已经装到别人机器上的内容不会被无声删除，他们下次同步会看到撤回提示。\n" +
			"只有属主能撤回自己的版本。",
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			id, err := ResolveSkillID(cmd.Context(), client, args[0])
			if err != nil {
				return err
			}
			result, err := client.RevokeVersion(cmd.Context(), id, args[1], reason)
			if err != nil {
				opLog().RecordFailure(oplog.ActionVersionRevoke, args[0], err.Error())
				return err
			}
			fmt.Printf("已撤回 %s@%s（%s）\n", args[0], args[1], result["status"])
			opLog().Record(oplog.ActionVersionRevoke, args[0], "版本已撤回",
				map[string]string{"skill_id": id, "version": args[1], "reason": reason})
			return nil
		},
	}
	cmd.Flags().StringVar(&reason, "reason", "", "撤回原因（记进服务端审计流水）")
	return cmd
}
