package cmd

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"

	"github.com/spf13/cobra"
)

// selfInstallName 是发布出去的 CLI 文件名。
func selfInstallName() string {
	name := "skillmesh"
	if runtime.GOOS == "windows" {
		name += ".exe"
	}
	return name
}

// cliDir 是 CLI 的固定可发现目录。
//
// 放在配置目录下：配置目录本来就是这台机器上 SkillMesh 的地盘，
// Agent 探测时只要认这一个路径。
func cliDir() string {
	return filepath.Join(Cfg.ConfigDir, "bin")
}

// cliStampPath 是版本标记的落点。
func cliStampPath() string {
	return filepath.Join(cliDir(), ".version")
}

func newSelfInstallCmd() *cobra.Command {
	var check bool
	cmd := &cobra.Command{
		Use:   "self-install",
		Short: "把 CLI 发布到固定路径，供 Agent 直接调用",
		Long: "Agent 找 CLI 需要一个稳定的路径：每次都问 PATH 太靠不住，\n" +
			"PATH 上的又可能是别的版本。\n\n" +
			"这个命令把当前这个可执行文件复制到固定目录，复制成功后才写版本标记——\n" +
			"标记在，说明那份是完整的；只有二进制没有标记，说明上次复制没成功，不能用。",
		Args: cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			if check {
				return reportSelfInstall()
			}
			return installSelf()
		},
	}
	cmd.Flags().BoolVar(&check, "check", false, "只看当前状态，不发布")
	return cmd
}

// installSelf 把当前可执行文件发布到固定路径。
//
// 顺序是有讲究的：先删旧标记，再复制，最后才写新标记。标记在，就说明
// 固定路径上那份是完整的——Agent 侧靠这个判断能不能用。
func installSelf() error {
	source, err := os.Executable()
	if err != nil {
		return fmt.Errorf("找不到当前可执行文件: %w", err)
	}
	dir := cliDir()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	target := filepath.Join(dir, selfInstallName())
	stamp := cliStampPath()

	// 标记先删：复制过程中它不能存在，否则会被当成「那份是完整的」。
	_ = os.Remove(stamp)

	temp, err := os.CreateTemp(dir, ".skillmesh-cli-*")
	if err != nil {
		return err
	}
	tempName := temp.Name()
	_ = temp.Close()
	defer os.Remove(tempName)

	if err := copyExecutable(source, tempName); err != nil {
		return err
	}
	// Windows 上 rename 不能覆盖已存在的文件，先把旧的挪走。
	_ = os.Remove(target)
	if err := os.Rename(tempName, target); err != nil {
		return fmt.Errorf("发布到 %s 失败: %w", target, err)
	}
	if err := os.WriteFile(stamp, []byte(cliVersion+"\n"), 0o644); err != nil {
		return fmt.Errorf("写版本标记失败: %w", err)
	}
	fmt.Printf("已发布 %s（版本 %s）\n", target, cliVersion)
	return nil
}

// copyExecutable 复制可执行文件并给上可执行权限。
func copyExecutable(source, target string) error {
	in, err := os.Open(source)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o755)
	if err != nil {
		return err
	}
	defer out.Close()
	if _, err := io.Copy(out, in); err != nil {
		return err
	}
	return os.Chmod(target, 0o755)
}

// reportSelfInstall 报告固定路径上的 CLI 处于什么状态。
//
// 三态：有标记 = 完整可用；只有二进制没有标记 = 上次复制没成功，不可用；
// 都没有 = 还没发布过。
func reportSelfInstall() error {
	target := filepath.Join(cliDir(), selfInstallName())
	version := ""
	hasStamp := false
	if data, err := os.ReadFile(cliStampPath()); err == nil {
		version = strings.TrimSpace(string(data))
		hasStamp = true
	}
	_, err := os.Stat(target)
	hasBinary := err == nil

	fmt.Printf("固定路径  %s\n", target)
	switch {
	case hasStamp && hasBinary:
		fmt.Printf("版本      %s\n状态      可用\n", version)
	case hasBinary:
		fmt.Println("状态      不可用：二进制在但没有版本标记，上次复制没成功")
	default:
		fmt.Println("状态      未发布")
	}
	return nil
}
