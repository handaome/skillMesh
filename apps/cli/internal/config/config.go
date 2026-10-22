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
	// DetectedDir 是启动时在本机探测到的目录（设计文档 §8：
	// 「按当前 Cursor 版本验证支持路径」）。空串表示没探测到，用的是配置值。
	DetectedDir string `yaml:"-"`
}

// 在本机上确实存在的目录才算探测到——目录不存在说明这个 Cursor 版本
// 可能根本不用它，写进去也没人读。
func (a Agent) Detected() bool { return a.DetectedDir != "" }

// dirExists 目录在不在。
func dirExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
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
			// Cursor 有两个候选用户级路径（设计文档 §8）：
			// ~/.cursor/skills 与 ~/.agents/skills。启动时探测哪个真的存在，
			// 探测到就用它，并在 agents detect 里说明用的是哪一个。
			"cursor": {
				Enabled:       true,
				UserSkillsDir: detectCursorDir(),
				ProjectSkills: filepath.Join(".cursor", "skills"),
			},
		},
		ConfigDir: configDir,
		CacheDir:  cacheDir,
	}
}

// detectCursorDir 在 Cursor 的候选目录里挑一个真的存在的。
func detectCursorDir() string {
	candidates := []string{
		filepath.Join(homeDir(), ".cursor", "skills"),
		filepath.Join(homeDir(), ".agents", "skills"),
	}
	for _, candidate := range candidates {
		if dirExists(candidate) {
			return candidate
		}
	}
	// 都没找到：按文档列的第一个来，装的时候目录会被创建。
	return candidates[0]
}

// markDetected 记录实际存在哪些 Agent 目录，供 agents detect 如实展示。
func markDetected(cfg *Config) {
	for name, agentCfg := range cfg.Agents {
		if dirExists(agentCfg.UserSkillsDir) {
			agentCfg.DetectedDir = agentCfg.UserSkillsDir
			cfg.Agents[name] = agentCfg
		}
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
		markDetected(&cfg) // 没配置文件也要探测：Cursor 路径本来就靠探测定
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
	// 钉住的验签公钥是信任根：读进来才叫钉住。
	// 漏了这一行，cfg.Verify 永远是零值，Pinned() 恒为 false——
	// 注册中心换把钥匙照样验签通过，信任根形同虚设。
	cfg.Verify = file.Verify
	cfg.Server = strings.TrimRight(cfg.Server, "/")
	markDetected(&cfg)
	return cfg, nil
}

func expandHome(path string) string {
	if path == "~" || strings.HasPrefix(path, "~/") || strings.HasPrefix(path, `~\`) {
		return filepath.Join(homeDir(), strings.TrimPrefix(strings.TrimPrefix(path, "~"), string(filepath.Separator)))
	}
	return path
}
