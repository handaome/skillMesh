// Package syncstate 记住「上次同步时的内容摘要」，用来判断这次同步
// 该上传、该下载，还是两边都改过了（设计文档 §6.3 冲突策略）。
//
// 状态放在配置目录而不是项目锁文件里：同步对象是 Agent 目录里的 Skill，
// 同一个 Skill 在不同项目里都装着一份，基线属于本机而不是某个项目。
package syncstate

import (
	"fmt"
	"os"
	"path/filepath"
	"time"

	"gopkg.in/yaml.v3"
)

const FileName = "sync-state.yaml"

// State 是本机所有已同步 Skill 的基线。
type State struct {
	UpdatedAt string  `yaml:"updated_at"`
	Skills    []Entry `yaml:"skills"`
}

// Entry 是一个 Skill 的同步基线。
type Entry struct {
	Name        string `yaml:"name"`
	Agent       string `yaml:"agent"`
	SkillID     string `yaml:"skill_id,omitempty"`
	BaseHash    string `yaml:"base_hash,omitempty"`
	LastVersion string `yaml:"last_version,omitempty"`
	SyncedAt    string `yaml:"synced_at,omitempty"`
}

func Path(configDir string) string {
	return filepath.Join(configDir, FileName)
}

func Load(configDir string) (State, error) {
	var state State
	data, err := os.ReadFile(Path(configDir))
	if os.IsNotExist(err) {
		return State{}, nil
	}
	if err != nil {
		return state, err
	}
	if err := yaml.Unmarshal(data, &state); err != nil {
		return state, fmt.Errorf("解析 %s: %w", FileName, err)
	}
	return state, nil
}

func (s State) Save(configDir string) error {
	s.UpdatedAt = time.Now().UTC().Format(time.RFC3339)
	data, err := yaml.Marshal(s)
	if err != nil {
		return err
	}
	return os.WriteFile(Path(configDir), data, 0o644)
}

// Find 按名字 + Agent 找基线。同名装给多个 Agent 时各自记一份。
func (s State) Find(name, agentName string) (Entry, bool) {
	for _, entry := range s.Skills {
		if entry.Name == name && entry.Agent == agentName {
			return entry, true
		}
	}
	return Entry{}, false
}

// Upsert 更新或新增一条基线。
func (s *State) Upsert(entry Entry) {
	entry.SyncedAt = time.Now().UTC().Format(time.RFC3339)
	for i := range s.Skills {
		if s.Skills[i].Name == entry.Name && s.Skills[i].Agent == entry.Agent {
			s.Skills[i] = entry
			return
		}
	}
	s.Skills = append(s.Skills, entry)
}
