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
	// PropagateDelete 开着就把「本地删掉的整个 Skill」也往云端传删除。
	//
	// 默认关着：目录扫不到可能只是路径变了、网盘没挂上，
	// 扫不到不等于用户删了它。开了之后，后台同步（watch）也会照做——
	// 这就是「明确配置并确认后才传播删除」里的那个「明确配置」。
	PropagateDelete bool `yaml:"propagate_delete"`
	// SharedDir 是所有 Agent 共用的技能目录。
	//
	// 技能的实体统一放这里，各 Agent 目录里放指向它的链接——
	// 同一份技能不用在每个 Agent 目录里各存一遍。
	//
	// nil 表示用默认值（~/.agent/skills）；指向空串表示明确关掉统一。
	// 用指针而不是 string：空串得能跟「没配」区分开。
	SharedDir *string `yaml:"shared_dir"`
}

// SharedSkillsDir 是共享目录的路径。没配就用默认的 ~/.agent/skills，
// 配成空串表示关掉统一。
func (s SyncConfig) SharedSkillsDir() string {
	if s.SharedDir == nil {
		return filepath.Join(homeDir(), ".agent", "skills")
	}
	return *s.SharedDir
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
		Sync:     SyncConfig{},
		Agents: map[string]Agent{
			"claude-code": {
				Enabled:       true,
				UserSkillsDir: filepath.Join(homeDir(), ".claude", "skills"),
				ProjectSkills: filepath.Join(".claude", "skills"),
				ExtraScanDirs: magpieLibrary(),
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

// magpieLibrary 是 magpie 管理器的 Skill 库目录。
//
// magpie 把技能本体放在 ~/.config/magpie/library/skills，再链接进各 Agent 目录。
// 链接在部分系统上连 filepath.Walk 都进不去，所以库目录本身也扫一遍——
// 同一个 Skill 被链接和库各扫到一次时，由 SyncTargets 按名字去重。
func magpieLibrary() []string {
	dir := filepath.Join(homeDir(), ".config", "magpie", "library", "skills")
	if dirExists(dir) {
		return []string{dir}
	}
	return nil
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
	return load("")
}

// load 读配置，可选地叠一层配置档。
func load(profileName string) (Config, error) {
	Cfg := defaults()
	applyEnv(&Cfg)
	file, err := readBaseConfig(Cfg.ConfigDir)
	if err != nil {
		return Cfg, err
	}
	if profileName != "" {
		profile, err := readProfile(filepath.Join(Cfg.ConfigDir, "profiles", profileName+".yaml"))
		if err != nil {
			return Cfg, err
		}
		file = mergeProfile(file, profile)
	}
	applyFile(&Cfg, file)
	Cfg.Server = strings.TrimRight(Cfg.Server, "/")
	markDetected(&Cfg)
	return Cfg, nil
}

// applyEnv 用环境变量盖住配置里的同名项。
func applyEnv(Cfg *Config) {
	if v := os.Getenv("SKILLMESH_SERVER"); v != "" {
		Cfg.Server = v
	}
	if v := os.Getenv("SKILLMESH_CLIENT_ID"); v != "" {
		Cfg.ClientID = v
	}
}

// readBaseConfig 读基础配置 config.yaml。
//
// 文件不存在不算错：没配过也是合法状态，用默认值就是。
func readBaseConfig(configDir string) (Config, error) {
	var file Config
	data, err := os.ReadFile(filepath.Join(configDir, "config.yaml"))
	if os.IsNotExist(err) {
		return file, nil
	}
	if err != nil {
		return file, fmt.Errorf("读取 config.yaml: %w", err)
	}
	if err := yaml.Unmarshal(data, &file); err != nil {
		return file, fmt.Errorf("解析 config.yaml: %w", err)
	}
	return file, nil
}

// applyFile 把配置文件里的项盖到默认值上。
func applyFile(Cfg *Config, file Config) {
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
	// 共享目录：没配就用默认的，配成空串就是明确关掉统一。
	if file.Sync.SharedDir != nil {
		shared := expandHome(*file.Sync.SharedDir)
		Cfg.Sync.SharedDir = &shared
	}
	// 删除传播：配了才算数，默认关着。
	if file.Sync.PropagateDelete {
		Cfg.Sync.PropagateDelete = true
	}
}

func expandHome(path string) string {
	if path == "~" || strings.HasPrefix(path, "~/") || strings.HasPrefix(path, `~\`) {
		return filepath.Join(homeDir(), strings.TrimPrefix(strings.TrimPrefix(path, "~"), string(filepath.Separator)))
	}
	return path
}

// SaveServer 把服务端地址写进 config.yaml，保留文件里已有的其他配置与注释。
//
// login <服务端地址> 之后必须记住它：登录的是远程服务端，后续 sync
// 不能还连默认的 localhost。
func SaveServer(server string) error {
	configDir, _ := DefaultPaths()
	path := filepath.Join(configDir, "config.yaml")

	var doc yaml.Node
	data, err := os.ReadFile(path)
	switch {
	case err == nil:
		if err := yaml.Unmarshal(data, &doc); err != nil {
			return fmt.Errorf("解析 config.yaml: %w", err)
		}
	case os.IsNotExist(err):
		doc = yaml.Node{Kind: yaml.DocumentNode, Content: []*yaml.Node{
			{Kind: yaml.MappingNode},
		}}
	default:
		return fmt.Errorf("读取 config.yaml: %w", err)
	}
	if len(doc.Content) == 0 {
		doc.Content = []*yaml.Node{{Kind: yaml.MappingNode}}
	}
	root := doc.Content[0]
	if root.Kind != yaml.MappingNode {
		return fmt.Errorf("config.yaml 顶层不是键值对")
	}

	value := strings.TrimRight(server, "/")
	for i := 0; i+1 < len(root.Content); i += 2 {
		if root.Content[i].Value != "server" {
			continue
		}
		root.Content[i+1].Value = value
		root.Content[i+1].Tag = "!!str"
		return writeYAML(path, &doc)
	}
	root.Content = append(root.Content,
		&yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: "server"},
		&yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: value})
	return writeYAML(path, &doc)
}

func writeYAML(path string, doc *yaml.Node) error {
	data, err := yaml.Marshal(doc)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o644)
}
