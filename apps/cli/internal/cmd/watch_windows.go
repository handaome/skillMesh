//go:build windows

package cmd

import (
	"bytes"
	"os/exec"
	"strconv"
	"syscall"
)

// detachAttr 让子进程独立出来，不随父进程退出而结束。
func detachAttr() *syscall.SysProcAttr {
	const createNewProcessGroup = 0x00000200
	const detachedProcess = 0x00000008
	return &syscall.SysProcAttr{CreationFlags: createNewProcessGroup | detachedProcess}
}

// processAlive 判断 PID 还在不在。Windows 没有 Unix 那套信号 0，
// 只能问一次任务列表。
func processAlive(pid int) bool {
	out, err := exec.Command("tasklist", "/FI", "PID eq "+strconv.Itoa(pid), "/NH").Output()
	if err != nil {
		return false
	}
	return bytes.Contains(out, []byte(strconv.Itoa(pid)))
}
