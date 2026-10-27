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
	DefaultServer   = "http://localhost:8080/api/v1"
	DefaultClientID = "skillmesh-cli"
)

// Config 是 CLI 配置。字段与 config.yaml 对应。
type Config struct {
	Server   string           `yaml:"server"`
	ClientID string           `yaml:"client_id"`
	Network  NetworkConfig    `yaml:"network"`
	Agents   map[string]Agent `yaml:"agents"`
	Verify   VerifyConfig     `yaml:"verify"`
	Sync     SyncConfig       `yaml:"sync"`

	ConfigDir string `yaml:"-"`
	CacheDir  string `yaml:"-"`
}

// SyncConfig 是本地同步的设置。
type SyncConfig struct {
	// ExtraDirs 是额外要扫描的目录（项目级、自定义目录）。
	//
	// Agent 适配器给出的用户级目录默认就扫；这里放的是**别的**地方——
	// 比如某个项目里的 .claude/skills，或团队约定的共享目录。
	// 设计文档 §6.1：支持用户级目录、项目级目录和自定义目录。
	ExtraDirs []string `yaml:"extra_dirs"`
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
	// ExtraScanDirs 是这个 Agent 额外的扫描根。
	//
	// 全局的那份是 sync.extra_dirs，这份挂在某个 Agent 名下——
	// 同一台机器上给不同的 Agent 扫不同的目录时用得上。
	ExtraScanDirs []string `yaml:"extra_scan_dirs,omitempty"`
	// RecursiveScan 控制扫这个 Agent 目录时要不要递归进子目录。
	//
	// nil 表示沿用默认（递归，深度上限 4）；显式 false 时只看直接子目录——
	// 有些 Agent 的 skills 目录是扁平的，递归会把技能自带的子目录也当成候选。
	// 用指针而不是 bool：bool 分不出「没配」与「显式关掉」。
	RecursiveScan *bool `yaml:"recursive_scan,omitempty"`
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
		Server:   DefaultServer,
		ClientID: DefaultClientID,
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
			//
			// 默认关着：设计文档 §4 把 Cursor 列在「后续增强」里（首期是
			// Claude Code 与 Codex），目录约定还没在真实安装上验证过。
			// 要用就在 config.yaml 里打开。
			"cursor": {
				Enabled:       false,
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
func markDetected(Cfg *Config) {
	for name, agentCfg := range Cfg.Agents {
		if dirExists(agentCfg.UserSkillsDir) {
			agentCfg.DetectedDir = agentCfg.UserSkillsDir
			Cfg.Agents[name] = agentCfg
		}
	}
}

// Load 按 环境变量 > 配置文件 > 默认值 的优先级读取配置。
func Load() (Config, error) {
	Cfg := defaults()
	if v := os.Getenv("SKILLMESH_SERVER"); v != "" {
		Cfg.Server = v
	}
	if v := os.Getenv("SKILLMESH_CLIENT_ID"); v != "" {
		Cfg.ClientID = v
	}

	data, err := os.ReadFile(filepath.Join(Cfg.ConfigDir, "config.yaml"))
	if os.IsNotExist(err) {
		markDetected(&Cfg) // 没配置文件也要探测：Cursor 路径本来就靠探测定
		return Cfg, nil
	}
	if err != nil {
		return Cfg, fmt.Errorf("读取 config.yaml: %w", err)
	}
	var file Config
	if err := yaml.Unmarshal(data, &file); err != nil {
		return Cfg, fmt.Errorf("解析 config.yaml: %w", err)
	}
	if file.Server != "" {
		Cfg.Server = file.Server
	}
	if file.ClientID != "" {
		Cfg.ClientID = file.ClientID
	}
	if file.Network.TimeoutSeconds > 0 {
		Cfg.Network = file.Network
	}
	for name, agent := range file.Agents {
		existing := Cfg.Agents[name]
		existing.Enabled = agent.Enabled
		if agent.UserSkillsDir != "" {
			existing.UserSkillsDir = expandHome(agent.UserSkillsDir)
		}
		if agent.ProjectSkills != "" {
			existing.ProjectSkills = agent.ProjectSkills
		}
		Cfg.Agents[name] = existing
	}
	// 钉住的验签公钥是信任根：读进来才叫钉住。
	// 漏了这一行，Cfg.Verify 永远是零值，Pinned() 恒为 false——
	// 注册中心换把钥匙照样验签通过，信任根形同虚设。
	Cfg.Verify = file.Verify
	// 额外扫描的目录：路径里的 ~ 要展开，跟 Agent 目录一样。
	for name, agentCfg := range file.Agents {
		for _, dir := range agentCfg.ExtraScanDirs {
			agentCfg.ExtraScanDirs = append(agentCfg.ExtraScanDirs, expandHome(dir))
		}
		file.Agents[name] = agentCfg
	}
	for _, dir := range file.Sync.ExtraDirs {
		if dir != "" {
			Cfg.Sync.ExtraDirs = append(Cfg.Sync.ExtraDirs, expandHome(dir))
		}
	}
	Cfg.Server = strings.TrimRight(Cfg.Server, "/")
	markDetected(&Cfg)
	return Cfg, nil
}

func expandHome(path string) string {
	if path == "~" || strings.HasPrefix(path, "~/") || strings.HasPrefix(path, `~\`) {
		return filepath.Join(homeDir(), strings.TrimPrefix(strings.TrimPrefix(path, "~"), string(filepath.Separator)))
	}
	return path
}
