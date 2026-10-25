package cmd

import (
	"fmt"
	"os"
	"text/tabwriter"

	"github.com/handaome/skillmesh/apps/cli/internal/trash"
	"github.com/spf13/cobra"
)

func newTrashCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "trash",
		Short: "回收站：卸载掉的 Skill 都在这儿",
		Long: "skillmesh uninstall 默认不删目录，移到回收站。\n" +
			"后悔了就 restore 回去；确认不要了用 purge 清掉过期条目。",
		Args: cobra.NoArgs,
	}
	cmd.AddCommand(trashListCmd(), trashRestoreCmd(), trashPurgeCmd())
	return cmd
}

func trashListCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "看回收站里有什么",
		Args:  cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			items, err := trash.NewStore(Cfg.ConfigDir).Items()
			if err != nil {
				return err
			}
			if len(items) == 0 {
				fmt.Println("回收站是空的。")
				return nil
			}
			w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
			fmt.Fprintln(w, "条目\t时间\t大小")
			for _, item := range items {
				fmt.Fprintf(w, "%s\t%s\t%d KiB\n", item.Entry,
					item.At.Format("2006-01-02 15:04"), item.SizeKiB)
			}
			return w.Flush()
		},
	}
}

func trashRestoreCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "restore <条目> <目标目录>",
		Short: "把回收站里的东西放回去",
		Args:  cobra.ExactArgs(2),
		RunE: func(_ *cobra.Command, args []string) error {
			if err := trash.NewStore(Cfg.ConfigDir).Restore(args[0], args[1]); err != nil {
				return err
			}
			fmt.Printf("已还原到 %s\n", args[1])
			return nil
		},
	}
}

func trashPurgeCmd() *cobra.Command {
	var days int
	cmd := &cobra.Command{
		Use:   "purge",
		Short: "清掉超过保留期的条目",
		Args:  cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			removed, err := trash.NewStore(Cfg.ConfigDir).Purge(days)
			if err != nil {
				return err
			}
			fmt.Printf("清掉 %d 条\n", removed)
			return nil
		},
	}
	cmd.Flags().IntVar(&days, "days", trash.RetentionDays, "保留天数")
	return cmd
}
