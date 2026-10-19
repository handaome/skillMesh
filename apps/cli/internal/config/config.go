// Package config 读取 CLI 的非敏感设置。凭据不进本文件，见 auth.Tokens。
package config

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"gopkg.in/yaml.v3"
)

const (
	defaultServer   = "http://localhost:8080/api/v1"
	defaultClientID = "skillmesh-cli"
)

// Config 是 CLI 配置。字段与 config.yaml 对应。
type Config struct {
	Server   string           `yaml:"server"`
	ClientID string           `yaml:"client_id"`
	Network  NetworkConfig    `yaml:"network"`
	Agents   map[string]Agent `yaml:"agents"`
	Verify   VerifyConfig     `yaml:"verify"`

	ConfigDir string `yaml:"-"`
	CacheDir  string `yaml:"-"`
}

// VerifyConfig 是钉住的验签公钥（信任根）。
//
// 填了就以本地这份为准：注册中心换密钥时必须先更新这里，
// 否则验签直接失败——这正是「信任根」该有的行为。
type VerifyConfig struct {
	PinnedKeyID     string `yaml:"pinned_key_id"`
	PinnedPublicKey string `yaml:"pinned_public_key"`
}

// Pinned 是否钉住了公钥。
func (v VerifyConfig) Pinned() bool {
	return v.PinnedKeyID != "" && v.PinnedPublicKey != ""
}

type NetworkConfig struct {
	TimeoutSeconds int `yaml:"timeout_seconds"`
	MaxRetries     int `yaml:"max_retries"`
}

// Agent 是单个 Agent 适配器的目录配置。路径全部来自配置，不在代码里硬编码。
type Agent struct {
	Enabled       bool   `yaml:"enabled"`
	UserSkillsDir string `yaml:"user_skills_dir"`
	ProjectSkills string `yaml:"project_skills_dir"`
}

func defaults() Config {
	configDir, cacheDir := DefaultPaths()
	return Config{
		Server:   defaultServer,
		ClientID: defaultClientID,
		Network:  NetworkConfig{TimeoutSeconds: 30, MaxRetries: 5},
		Verify:   VerifyConfig{},
		Agents: map[string]Agent{
			"claude-code": {
				Enabled:       true,
				UserSkillsDir: filepath.Join(homeDir(), ".claude", "skills"),
				ProjectSkills: filepath.Join(".claude", "skills"),
			},
			"codex": {
				Enabled:       true,
				UserSkillsDir: filepath.Join(codexHome(), "skills"),
				ProjectSkills: filepath.Join(".agents", "skills"),
			},
			// Cursor 的 Skill 目录约定随版本变过，这里用设计文档 §8 记的
			// ~/.cursor/skills 与 .cursor/skills，落到别的路径就在 config.yaml 里改。
			"cursor": {
				Enabled:       false, // 默认关着：没验证过的适配器不该替用户决定
				UserSkillsDir: filepath.Join(homeDir(), ".cursor", "skills"),
				ProjectSkills: filepath.Join(".cursor", "skills"),
			},
		},
		ConfigDir: configDir,
		CacheDir:  cacheDir,
	}
}

// Load 按 环境变量 > 配置文件 > 默认值 的优先级读取配置。
func Load() (Config, error) {
	cfg := defaults()
	if v := os.Getenv("SKILLMESH_SERVER"); v != "" {
		cfg.Server = v
	}
	if v := os.Getenv("SKILLMESH_CLIENT_ID"); v != "" {
		cfg.ClientID = v
	}

	data, err := os.ReadFile(filepath.Join(cfg.ConfigDir, "config.yaml"))
	if os.IsNotExist(err) {
		return cfg, nil
	}
	if err != nil {
		return cfg, fmt.Errorf("读取 config.yaml: %w", err)
	}
	var file Config
	if err := yaml.Unmarshal(data, &file); err != nil {
		return cfg, fmt.Errorf("解析 config.yaml: %w", err)
	}
	if file.Server != "" {
		cfg.Server = file.Server
	}
	if file.ClientID != "" {
		cfg.ClientID = file.ClientID
	}
	if file.Network.TimeoutSeconds > 0 {
		cfg.Network = file.Network
	}
	for name, agent := range file.Agents {
		existing := cfg.Agents[name]
		existing.Enabled = agent.Enabled
		if agent.UserSkillsDir != "" {
			existing.UserSkillsDir = expandHome(agent.UserSkillsDir)
		}
		if agent.ProjectSkills != "" {
			existing.ProjectSkills = agent.ProjectSkills
		}
		cfg.Agents[name] = existing
	}
	cfg.Server = strings.TrimRight(cfg.Server, "/")
	return cfg, nil
}

func expandHome(path string) string {
	if path == "~" || strings.HasPrefix(path, "~/") || strings.HasPrefix(path, `~\`) {
		return filepath.Join(homeDir(), strings.TrimPrefix(strings.TrimPrefix(path, "~"), string(filepath.Separator)))
	}
	return path
}
