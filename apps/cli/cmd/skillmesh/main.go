package main

import (
	"fmt"
	"os"

	"github.com/handaome/skillmesh/apps/cli/internal/cmd"
)

func main() {
	if err := run(); err != nil {
		os.Exit(cmd.ExitCode(err))
	}
}

// run 包一层 recover：CLI 崩了不能只留一句 panic。
//
// 崩在哪一行才是排障要的东西，所以把调用栈写进 last_panic.log——
// 路径已脱敏，那份日志是要往外发的。
func run() (err error) {
	defer func() {
		if r := recover(); r != nil {
			cmd.WritePanicLog(r)
			fmt.Fprintln(os.Stderr, "崩溃：详情已写入", cmd.PanicLogPath())
			os.Exit(1)
		}
	}()
	return cmd.Execute()
}
