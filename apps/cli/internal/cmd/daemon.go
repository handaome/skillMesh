package cmd

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/spf13/cobra"
)

func newDaemonCmd() *cobra.Command {
	var interval time.Duration
	cmd := &cobra.Command{
		Use:   "daemon",
		Short: "把监听装成用户级常驻服务",
		Long: "install 只动用户级自启动配置，不要求管理员权限。\n" +
			"系统不支持服务注册时（比如没装任务计划程序），保留前台运行模式。",
		Args: cobra.NoArgs,
	}
	cmd.AddCommand(daemonRunCmd(&interval), daemonInstallCmd(), daemonUninstallCmd(), daemonLogsCmd())
	return cmd
}

func daemonRunCmd(interval *time.Duration) *cobra.Command {
	return &cobra.Command{
		Use:   "run",
		Short: "前台运行（等价于 watch run）",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if *interval <= 0 {
				*interval = defaultWatchInterval
			}
			return runWatchLoop(cmd.Context(), *interval)
		},
	}
}

func daemonInstallCmd() *cobra.Command {
	var interval time.Duration
	cmd := &cobra.Command{
		Use:   "install",
		Short: "注册用户级自启动",
		Args:  cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			exe, err := os.Executable()
			if err != nil {
				return err
			}
			line := fmt.Sprintf("start \"\" \"%s\" watch run --interval %s", exe, interval)
			path, err := startupEntryPath()
			if err != nil {
				return err
			}
			if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
				return err
			}
			body := "@echo off\r\nrem SkillMesh 同步守护进程（用户级，不需要管理员）\r\n" +
				line + "\r\n"
			if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
				return err
			}
			fmt.Printf("已写入用户级自启动项：%s\n下次登录时自动启动。\n", path)
			return nil
		},
	}
	cmd.Flags().DurationVar(&interval, "interval", defaultWatchInterval, "检查间隔")
	return cmd
}

func daemonUninstallCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "uninstall",
		Short: "移除自启动项",
		Args:  cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			path, err := startupEntryPath()
			if err != nil {
				return err
			}
			if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
				return err
			}
			_ = os.Remove(watchPidPath())
			fmt.Println("已移除自启动项。正在运行的进程请用 skillmesh watch stop。")
			return nil
		},
	}
}

func daemonLogsCmd() *cobra.Command {
	var tail int
	cmd := &cobra.Command{
		Use:   "logs",
		Short: "看守护进程日志",
		Args:  cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			data, err := os.ReadFile(watchLogPath())
			if err != nil {
				return fmt.Errorf("还没有日志（守护进程可能没跑过）: %w", err)
			}
			lines := strings.Split(strings.TrimRight(string(data), "\n"), "\n")
			if tail > 0 && len(lines) > tail {
				lines = lines[len(lines)-tail:]
			}
			for _, line := range lines {
				fmt.Println(line)
			}
			return nil
		},
	}
	cmd.Flags().IntVar(&tail, "tail", 50, "只看最后 N 行")
	return cmd
}

// startupEntryPath 返回用户级自启动脚本的位置。Windows 走启动文件夹，
// 其他平台退回 ~/.config 下的说明文件——不猜平台机制，给能看懂的东西。
func startupEntryPath() (string, error) {
	if runtime.GOOS == "windows" {
		appData := os.Getenv("APPDATA")
		if appData == "" {
			return "", fmt.Errorf("APPDATA 为空，找不到启动文件夹")
		}
		return filepath.Join(appData, "Microsoft", "Windows", "Start Menu",
			"Programs", "Startup", "skillmesh-watch.cmd"), nil
	}
	home := os.Getenv("HOME")
	if home == "" {
		return "", fmt.Errorf("HOME 为空")
	}
	return filepath.Join(home, ".config", "skillmesh", "watch-start.sh"), nil
}
