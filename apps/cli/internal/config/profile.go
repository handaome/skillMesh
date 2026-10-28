package config

import (
	"fmt"
	"os"
	"path/filepath"

	"gopkg.in/yaml.v3"
)

// LoadProfile 按名字加载配置档：<配置目录>/profiles/<name>.yaml。
//
// 配置档是叠在 config.yaml 之上的一层，只写要改的项：没写的沿用基础配置。
// 这样「换个服务端试一下」不用把整套目录配置重抄一遍。
//
// 名字为空时就是普通的基础配置。
func LoadProfile(name string) (Config, error) {
	return load(name)
}

// profileAgent 是配置档里单个 Agent 的写法。
//
// enabled 用指针：布尔零值分不出「没写」与「显式关掉」，
// 而配置档里「把这个 Agent 关掉」正是要表达的意思。
type profileAgent struct {
	Enabled       *bool    `yaml:"enabled"`
	UserSkillsDir string   `yaml:"user_skills_dir"`
	ProjectSkills string   `yaml:"project_skills_dir"`
	ExtraScanDirs []string `yaml:"extra_scan_dirs"`
	RecursiveScan *bool    `yaml:"recursive_scan"`
}

// profileFile 是配置档的结构，字段与 config.yaml 同名。
type profileFile struct {
	Server   string                  `yaml:"server"`
	ClientID string                  `yaml:"client_id"`
	Network  NetworkConfig           `yaml:"network"`
	Agents   map[string]profileAgent `yaml:"agents"`
	Verify   VerifyConfig            `yaml:"verify"`
	Sync     SyncConfig              `yaml:"sync"`
}

// readProfile 读配置档。文件不存在是错：指名要一个档却找不到，
// 静默退回基础配置的话，用户会以为测的是新配置。
func readProfile(path string) (profileFile, error) {
	var profile profileFile
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return profile, fmt.Errorf("没有配置档 %s（找 %s）", filepath.Base(path), path)
	}
	if err != nil {
		return profile, fmt.Errorf("读取配置档 %s: %w", path, err)
	}
	if err := yaml.Unmarshal(data, &profile); err != nil {
		return profile, fmt.Errorf("解析配置档 %s: %w", path, err)
	}
	return profile, nil
}

// mergeProfile 把配置档里的项盖到基础配置上。
//
// 规则是「只写了的项才生效」：空值等于没写，基础配置原样保留。
// 唯一例外是 enabled——它用指针记，写了 false 就是显式关掉。
func mergeProfile(base Config, profile profileFile) Config {
	if profile.Server != "" {
		base.Server = profile.Server
	}
	if profile.ClientID != "" {
		base.ClientID = profile.ClientID
	}
	mergeNetwork(&base.Network, profile.Network)
	for name, item := range profile.Agents {
		base.Agents[name] = mergeAgent(base.Agents[name], item)
	}
	// 验签公钥是信任根：配置档钉了就以它为准。
	if profile.Verify.PinnedKeyID != "" || profile.Verify.PinnedPublicKey != "" {
		base.Verify = profile.Verify
	}
	if len(profile.Sync.ExtraDirs) > 0 {
		base.Sync.ExtraDirs = profile.Sync.ExtraDirs
	}
	if profile.Sync.PropagateDelete {
		base.Sync.PropagateDelete = true
	}
	return base
}

// mergeNetwork 逐项合并网络配置：只写了的项才生效。
func mergeNetwork(base *NetworkConfig, profile NetworkConfig) {
	if profile.TimeoutSeconds > 0 {
		base.TimeoutSeconds = profile.TimeoutSeconds
	}
	if profile.MaxRetries > 0 {
		base.MaxRetries = profile.MaxRetries
	}
}

// mergeAgent 把配置档里的 Agent 设置盖到基础设置上。
func mergeAgent(base Agent, item profileAgent) Agent {
	if item.Enabled != nil {
		base.Enabled = *item.Enabled
	}
	if item.UserSkillsDir != "" {
		base.UserSkillsDir = item.UserSkillsDir
	}
	if item.ProjectSkills != "" {
		base.ProjectSkills = item.ProjectSkills
	}
	if item.ExtraScanDirs != nil {
		base.ExtraScanDirs = item.ExtraScanDirs
	}
	if item.RecursiveScan != nil {
		base.RecursiveScan = item.RecursiveScan
	}
	return base
}
