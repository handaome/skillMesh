package cmd

import (
	"fmt"
	"os"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/spf13/cobra"
)

func newInstallationsCmd() *cobra.Command {
	var status string
	var check bool
	cmd := supportsJSON(&cobra.Command{
		Use:   "installations",
		Short: "看我在各台机器上装了什么",
		Long: "install / uninstall 时会自动登记，这里能跨设备看到全貌：\n" +
			"装在哪台机器、哪个目录、什么版本、有没有被我改过。\n\n" +
			"--check 巡检本机磁盘：目录还在不在、内容被改过没有、装的那一版还能不能用，\n" +
			"算出来的真状态会写回服务端。",
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			// 巡检时先拿全量：服务端记的状态正是要重算的东西，
			// 按旧状态筛会把要修的记录挡在门外。
			query := status
			if check {
				query = ""
			}
			rows, err := client.Installations(cmd.Context(), query)
			if err != nil {
				return err
			}
			if check {
				rows, notes := CheckInstallations(cmd.Context(), client, rows)
				for _, note := range notes {
					notice("%s\n", note)
				}
				rows = filterByStatus(rows, status)
			}
			if jsonEnabled() {
				return writeJSON(map[string]any{"items": jsonItems(rows)})
			}
			return printInstallationTable(rows)
		},
	})
	cmd.Flags().StringVar(&status, "status", "", "按状态过滤：active / missing / conflict / uninstalled / blocked")
	cmd.Flags().BoolVar(&check, "check", false, "巡检本机磁盘，把真实状态写回服务端")
	return cmd
}

// printInstallationTable 打印人读表格。
func printInstallationTable(rows []apiclient.Installation) error {
	if len(rows) == 0 {
		fmt.Println("注册中心还没有你的安装记录。")
		return nil
	}
	w := newTable(os.Stdout)
	fmt.Fprintln(w, "SKILL\t版本\tAGENT\t设备\t作用域\t状态\t路径")
	for _, row := range rows {
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\t%s\t%s\n",
			short(row.SkillID), orDash(row.InstalledVersion), row.Agent,
			short(row.DeviceID), row.InstallScope,
			installationState(row), row.TargetPath)
	}
	return w.Flush()
}

// installationState 是列表里显示的状态：服务端记的状态，加上「本地改过」。
//
// 状态列要显示真话：巡检之后 active/missing/conflict/uninstalled/blocked
// 都是算出来的，不是服务端那个默认值。
func installationState(row apiclient.Installation) string {
	state := row.Status
	if state == "" {
		state = "active"
	}
	if state == "active" && row.LocalModified {
		return "active（本地改过）"
	}
	return state
}
