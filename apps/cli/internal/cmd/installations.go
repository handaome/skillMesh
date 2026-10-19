package cmd

import (
	"fmt"
	"os"
	"text/tabwriter"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/spf13/cobra"
)

func newInstallationsCmd() *cobra.Command {
	var status string
	cmd := &cobra.Command{
		Use:   "installations",
		Short: "看我在各台机器上装了什么",
		Long: "install / uninstall 时会自动登记，这里能跨设备看到全貌：\n" +
			"装在哪台机器、哪个目录、什么版本、有没有被我改过。",
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			client, err := newClient()
			if err != nil {
				return err
			}
			rows, err := client.Installations(cmd.Context(), status)
			if err != nil {
				return err
			}
			if len(rows) == 0 {
				fmt.Println("注册中心还没有你的安装记录。")
				return nil
			}
			w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
			fmt.Fprintln(w, "SKILL\t版本\tAGENT\t设备\t作用域\t状态\t路径")
			for _, row := range rows {
				fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\t%s\t%s\n",
					short(row.SkillID), orDash(row.InstalledVersion), row.Agent,
					short(row.DeviceID), row.InstallScope,
					installationState(row), row.TargetPath)
			}
			return w.Flush()
		},
	}
	cmd.Flags().StringVar(&status, "status", "", "按状态过滤：active / missing / conflict / uninstalled / blocked")
	return cmd
}

func installationState(row apiclient.Installation) string {
	if row.Status != "active" {
		return row.Status
	}
	if row.LocalModified {
		return "本地改过"
	}
	return row.Status
}
