package cmd

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/handaome/skillmesh/apps/cli/internal/redact"
)

// PanicLogName 是崩溃日志的文件名。
const PanicLogName = "last_panic.log"

// PanicLogPath 返回崩溃日志的落点。
func PanicLogPath() string {
	dir := Cfg.ConfigDir
	if dir == "" {
		// 崩在配置加载之前时还不知道配置目录，退回主目录下的 .skillmesh。
		home, err := os.UserHomeDir()
		if err != nil {
			return ""
		}
		dir = filepath.Join(home, ".skillmesh")
	}
	return filepath.Join(dir, PanicLogName)
}

// WritePanicLog 把崩溃信息落盘。
//
// 写不了就算了：崩了还要再崩一次更糟。
func WritePanicLog(value any) {
	path := PanicLogPath()
	if path == "" {
		return
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return
	}
	content := fmt.Sprintf("time: %s\npanic: %s\n\n%s",
		time.Now().Format(time.RFC3339), redact.Text(fmt.Sprint(value)), stackTrace())
	_ = os.WriteFile(path, []byte(content), 0o644)
}

// stackTrace 取崩溃时的调用栈，只留函数名。
//
// debug.Stack() 里带着本机的源码绝对路径（用户名、目录结构），
// 崩溃日志是要往外发的，不能带这些。
func stackTrace() string {
	pcs := make([]uintptr, 32)
	n := runtime.Callers(3, pcs)
	frames := runtime.CallersFrames(pcs[:n])
	var sb strings.Builder
	for {
		frame, more := frames.Next()
		if frame.Function != "" {
			sb.WriteString(frame.Function)
			sb.WriteString("\n")
		}
		if !more {
			break
		}
	}
	return sb.String()
}
