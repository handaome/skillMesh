package cmd

import (
	"fmt"
	"os"
	"time"

	"github.com/handaome/skillmesh/apps/cli/internal/oplog"
	"github.com/spf13/cobra"
)

// newLogsCmd 看本机的 Skill 操作日志。
//
// 服务端那边是审计流水（谁对哪个 Skill 做了什么），这里是本机日志
// （这台机器上同步把哪份删了、装到哪儿了、哪步失败了）。
func newLogsCmd() *cobra.Command {
	var tail int
	var skill string
	cmd := &cobra.Command{
		Use:   "logs",
		Short: "看本机的 Skill 操作日志",
		Long: "本机发生过什么：同步的上传/拉取/删除、发布、安装、卸载。\n" +
			"服务端的审计流水记的是「谁对哪个 Skill 做了什么」，\n" +
			"这份记的是本机的细节——比如云端删了之后本地那份被移进了回收站。",
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			path := opLogPath()
			entries := oplog.Tail(path, tail, skill)
			if len(entries) == 0 {
				if _, err := os.Stat(path); err != nil {
					fmt.Printf("还没有日志：%s\n", path)
					return nil
				}
				fmt.Println("日志是空的。")
				return nil
			}
			w := newTable(os.Stdout)
			fmt.Fprintln(w, "时间\t动作\tSKILL\t结果\t说明")
			for _, entry := range entries {
				fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\n",
					logTime(entry.Time), entry.Action, entry.Skill,
					entry.Outcome, entry.Detail)
			}
			return w.Flush()
		},
	}
	cmd.Flags().IntVar(&tail, "tail", 50, "看最后多少条")
	cmd.Flags().StringVar(&skill, "skill", "", "只看这个 Skill")
	return cmd
}

// logTime 把日志里的 UTC 时间转成当地的时间显示。
//
// 存 UTC 是为了跨时区可比，显示要看本地的——查事的时候脑子里的时间轴
// 是本地时间。
func logTime(value string) string {
	parsed, err := time.Parse(time.RFC3339, value)
	if err != nil {
		return value
	}
	return parsed.Local().Format("2006-01-02 15:04:05")
}
