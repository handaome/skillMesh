package cmd

import (
	"fmt"
	"os"
	"text/tabwriter"

	"github.com/spf13/cobra"
)

func newSearchCmd() *cobra.Command {
	var visibility, tag string
	var limit int
	cmd := &cobra.Command{
		Use:   "search [关键字]",
		Short: "搜索可见的 Skill",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client, err := newClient()
			if err != nil {
				return err
			}
			keyword := ""
			if len(args) == 1 {
				keyword = args[0]
			}
			page, err := client.Search(cmd.Context(), keyword, visibility, tag, limit, "")
			if err != nil {
				return err
			}
			if len(page.Items) == 0 {
				fmt.Println("没有匹配的 Skill")
				return nil
			}
			w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
			fmt.Fprintln(w, "SLUG\t名称\t可见性\t最新版本\tID")
			for _, skill := range page.Items {
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
			if page.HasMore {
				fmt.Println("还有更多结果，请缩小关键字范围。")
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&visibility, "visibility", "", "按可见性过滤：private/team/organization/public")
	cmd.Flags().StringVar(&tag, "tag", "", "按标签过滤")
	cmd.Flags().IntVar(&limit, "limit", 20, "返回条数")
	return cmd
}
