package cmd

import (
	"archive/zip"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/redact"
)

// exportBundle 打包一份诊断信息，供用户提交 issue 用。
//
// 打包的不是原始文件：日志再脱敏一遍（脱敏功能上线前写的历史日志没有脱敏），
// 配置里的家目录换成 ~。诊断包是要往外发的。
func exportBundle(path string) error {
	if path == "" {
		return fmt.Errorf("诊断包路径为空")
	}
	if !strings.HasSuffix(strings.ToLower(path), ".zip") {
		path += ".zip"
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()

	writer := zip.NewWriter(file)
	defer writer.Close()

	if err := addText(writer, "version.txt", bundleVersion()); err != nil {
		return err
	}
	if err := addText(writer, "config.yaml", bundleConfig()); err != nil {
		return err
	}
	if err := addText(writer, "agents.txt", bundleAgents()); err != nil {
		return err
	}
	if err := addText(writer, "operations.log", bundleLog()); err != nil {
		return err
	}
	return nil
}

// addText 往包里放一个文本文件。
func addText(writer *zip.Writer, name, content string) error {
	entry, err := writer.Create(name)
	if err != nil {
		return err
	}
	_, err = entry.Write([]byte(content))
	return err
}

// bundleVersion 版本与运行环境。
func bundleVersion() string {
	return strings.Join([]string{
		"skillmesh " + cliVersion,
		"go " + runtime.Version(),
		"platform " + runtime.GOOS + "/" + runtime.GOARCH,
		"time " + time.Now().Format(time.RFC3339),
		"",
	}, "\n")
}

// bundleConfig 脱敏后的配置。
//
// 家目录换成 ~、令牌类遮蔽：诊断包是要往外发的，完整的目录布局不该跟着出去。
func bundleConfig() string {
	raw, err := os.ReadFile(filepath.Join(Cfg.ConfigDir, "config.yaml"))
	if err != nil {
		// 错误信息里带着配置目录的绝对路径——诊断包是要往外发的。
		return "读不到配置文件: " + redact.Text(err.Error()) + "\n"
	}
	return redact.Text(string(raw))
}

// bundleAgents 各 Agent 的目录与状态。
func bundleAgents() string {
	var sb strings.Builder
	for _, name := range agent.Names(Cfg) {
		agentCfg := Cfg.Agents[name]
		sb.WriteString(name)
		sb.WriteString("\t")
		sb.WriteString(fmt.Sprintf("enabled=%v", agentCfg.Enabled))
		sb.WriteString("\t")
		sb.WriteString(redact.Path(agentCfg.UserSkillsDir))
		sb.WriteString("\t")
		sb.WriteString(agentCfg.ProjectSkills)
		sb.WriteString("\t")
		sb.WriteString(DirState(agentCfg.UserSkillsDir))
		sb.WriteString("\n")
	}
	return sb.String()
}

// bundleLog 脱敏后的本机日志。
func bundleLog() string {
	raw, err := os.ReadFile(opLogPath())
	if err != nil {
		return "读不到日志: " + redact.Text(err.Error()) + "\n"
	}
	// 一行一条 JSON，逐行脱敏：日志里的路径与令牌不能跟着诊断包出去。
	lines := strings.Split(string(raw), "\n")
	for i, line := range lines {
		if strings.TrimSpace(line) == "" {
			continue
		}
		lines[i] = redact.Text(line)
	}
	return strings.Join(lines, "\n")
}
