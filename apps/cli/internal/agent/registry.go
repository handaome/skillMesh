// Package agent 解析目标 Agent 的 Skill 安装目录。路径全部来自配置，
// 不在业务代码里硬编码（详细设计 附录 A.8）。
package agent

import (
	"fmt"
	"path/filepath"
	"sort"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/config"
)

// Supported 是已知的 Agent 适配器（设计文档 §8）。
// 是否启用看配置——Cursor 默认关着，目录约定还没在真实安装上验证过。
var Supported = []string{"claude-code", "codex", "cursor"}

// Dir 解析目标 Agent 的安装目录。scope 为 project 时用项目内相对路径。
func Dir(Cfg config.Config, name, scope, projectRoot string) (string, error) {
	agentCfg, ok := Cfg.Agents[name]
	if !ok {
		return "", fmt.Errorf("未知 Agent %q，支持的是 %s",
			name, strings.Join(supportedNames(Cfg), ", "))
	}
	if !agentCfg.Enabled {
		return "", fmt.Errorf("Agent %s 在 config.yaml 中未启用", name)
	}
	if scope == "project" {
		if projectRoot == "" {
			return "", fmt.Errorf("未检测到项目根目录，请显式指定 --scope user")
		}
		return filepath.Join(projectRoot, agentCfg.ProjectSkills), nil
	}
	return agentCfg.UserSkillsDir, nil
}

func supportedNames(Cfg config.Config) []string {
	names := make([]string, 0, len(Cfg.Agents))
	for name, agentCfg := range Cfg.Agents {
		if agentCfg.Enabled {
			names = append(names, name)
		}
	}
	sort.Strings(names)
	return names
}
