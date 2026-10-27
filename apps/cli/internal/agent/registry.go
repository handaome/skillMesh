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

// Names 列出配置里所有的 Agent（含未启用的）。
//
// 不用硬编码清单：config.yaml 的 agents 段是开放的，企业内网自建的 Agent
// 也在里面——拿一份写死的清单，agents detect、scan --all-agents、sync
// 就都看不见它们，而 doctor 却看得见（doctor 读的是配置），两边对不上。
func Names(Cfg config.Config) []string {
	names := make([]string, 0, len(Cfg.Agents))
	for name := range Cfg.Agents {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}

// RecursiveScan 判断扫这个 Agent 的目录时要不要递归进子目录。
//
// 没配就是递归（沿用原行为）；显式配成 false 时只看直接子目录。
func RecursiveScan(agentCfg config.Agent) bool {
	if agentCfg.RecursiveScan == nil {
		return true
	}
	return *agentCfg.RecursiveScan
}

// ScanDirs 是这个 Agent 要扫的目录：主目录加上它自己配置的额外扫描根。
//
// 全局的那份额外扫描根（sync.extra_dirs）由调用方另算——它是所有 Agent 共用的。
func ScanDirs(agentCfg config.Agent) []string {
	dirs := []string{agentCfg.UserSkillsDir}
	for _, dir := range agentCfg.ExtraScanDirs {
		if dir != "" {
			dirs = append(dirs, dir)
		}
	}
	return dirs
}

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
