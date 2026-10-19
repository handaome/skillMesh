//go:build !windows

package cmd

import (
	"os"
	"syscall"
)

// detachAttr 让子进程脱离当前进程组，父进程退出后它继续跑。
func detachAttr() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{Setsid: true}
}

// processAlive 用信号 0 探活：进程不在会返回 ESRCH。
func processAlive(pid int) bool {
	process, err := os.FindProcess(pid)
	if err != nil {
		return false
	}
	return process.Signal(syscall.Signal(0)) == nil
}
