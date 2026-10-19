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

// Supported 是 v1 交付的两个 Agent（详细设计 §4 首期范围）。
var Supported = []string{"claude-code", "codex"}

// Dir 解析目标 Agent 的安装目录。scope 为 project 时用项目内相对路径。
func Dir(cfg config.Config, name, scope, projectRoot string) (string, error) {
	agentCfg, ok := cfg.Agents[name]
	if !ok {
		return "", fmt.Errorf("未知 Agent %q，支持的是 %s",
			name, strings.Join(supportedNames(cfg), ", "))
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

func supportedNames(cfg config.Config) []string {
	names := make([]string, 0, len(cfg.Agents))
	for name, agentCfg := range cfg.Agents {
		if agentCfg.Enabled {
			names = append(names, name)
		}
	}
	sort.Strings(names)
	return names
}
