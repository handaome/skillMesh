package cmd

import (
	"fmt"

	"github.com/spf13/cobra"
)

// reviewAction 是审核动作：提交、通过、拒绝。
var reviewActions = map[string]string{
	"submit":  "提交",
	"approve": "通过",
	"reject":  "拒绝",
}

func newReviewCmd() *cobra.Command {
	var comment string
	cmd := &cobra.Command{
		Use:   "review <submit|approve|reject> <skill-id> <version>",
		Short: "提交或审核团队版本的发布",
		Long: "团队与组织可见的 Skill 发上去会停在待审核：\n" +
			"  submit   把版本送去审核（publisher 及以上）\n" +
			"  approve  审核通过并发布（reviewer 及以上，且不能审自己发的）\n" +
			"  reject   审核拒绝\n" +
			"个人与公开的 Skill 不走审核，扫描通过即发布。",
		Args: cobra.ExactArgs(3),
		RunE: func(cmd *cobra.Command, args []string) error {
			action, known := reviewActions[args[0]]
			if !known {
				return fmt.Errorf("动作只能是 submit / approve / reject，收到 %q", args[0])
			}
			client, err := newClient()
			if err != nil {
				return err
			}
			result, err := client.ReviewVersion(cmd.Context(), args[1], args[2], args[0], comment)
			if err != nil {
				return err
			}
			fmt.Printf("%s %s@%s -> %v\n", action, args[1], args[2], result["status"])
			return nil
		},
	}
	cmd.Flags().StringVar(&comment, "comment", "", "审核意见 / 说明")
	return cmd
}
