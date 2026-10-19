package cmd

import (
	"fmt"
	"os"
	"text/tabwriter"

	"github.com/spf13/cobra"
)

func newInfoCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "info <skill-id>",
		Short: "查看 Skill 详情与版本列表",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client, err := newClient()
			if err != nil {
				return err
			}
			skill, err := client.Skill(cmd.Context(), args[0])
			if err != nil {
				return err
			}
			fmt.Printf("%s (%s)\n%s\n", skill.Name, skill.Slug, skill.Description)

			versions, err := client.Versions(cmd.Context(), args[0])
			if err != nil {
				return err
			}
			if len(versions.Items) == 0 {
				fmt.Println("暂无版本")
				return nil
			}
			w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
			fmt.Fprintln(w, "版本\t通道\t状态\tcommit\tSHA256")
			for _, version := range versions.Items {
				commit := "-"
				if version.SourceCommit != nil {
					commit = short(*version.SourceCommit)
				}
				digest := "-"
				if version.Artifact != nil {
					digest = version.Artifact.SHA256[:12]
				}
				fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\n",
					version.Version, version.Channel, version.Status, commit, digest)
			}
			return w.Flush()
		},
	}
}
