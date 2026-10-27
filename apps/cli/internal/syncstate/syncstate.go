// Package syncstate 记住「上次同步时的内容摘要」，用来判断这次同步
// 该上传、该下载，还是两边都改过了（设计文档 §6.3 冲突策略）。
//
// 状态放在配置目录而不是项目锁文件里：同步对象是 Agent 目录里的 Skill，
// 同一个 Skill 在不同项目里都装着一份，基线属于本机而不是某个项目。
package syncstate

import (
	"path/filepath"
	"time"

	"github.com/handaome/skillmesh/apps/cli/internal/statefile"
)

const FileName = "sync-state.yaml"

// State 是本机所有已同步 Skill 的基线。
type State struct {
	UpdatedAt string  `yaml:"updated_at"`
	Skills    []Entry `yaml:"skills"`
}

// Entry 是一个 Skill 的同步基线。
type Entry struct {
	Name     string `yaml:"name"`
	Agent    string `yaml:"agent"`
	SkillID  string `yaml:"skill_id,omitempty"`
	BaseHash string `yaml:"base_hash,omitempty"`
	// Path 是本地目录的绝对路径。云端把这个 Skill 删了时，
	// 要知道去哪把它移进本机回收站——光有名字找不到地方。
	Path string `yaml:"path,omitempty"`
	// BaseFiles 是上次同步时每个文件的摘要，冲突时用来判断「谁改了哪些文件」
	// （设计文档 §6.3：已变更且不同文件 → 按文件合并）。
	BaseFiles map[string]string `yaml:"base_files,omitempty"`
	// BaseSnapshot 是上次同步内容的本地副本，三方文本合并要读它。
	BaseSnapshot string `yaml:"base_snapshot,omitempty"`
	LastVersion  string `yaml:"last_version,omitempty"`
	// FirstSeenAt 是第一次在授权目录里发现它的时间，与 SyncedAt 分开：
	// 发现 ≠ 收编，用户可以先跳过（设计文档 §3.1 首次发现策略）。
	FirstSeenAt string `yaml:"first_seen_at,omitempty"`
	SyncedAt    string `yaml:"synced_at,omitempty"`
}

func Path(configDir string) string {
	return filepath.Join(configDir, FileName)
}

func Load(configDir string) (State, error) {
	return statefile.Load(Path(configDir), FileName, State{})
}

func (s State) Save(configDir string) error {
	s.UpdatedAt = time.Now().UTC().Format(time.RFC3339)
	return statefile.Save(Path(configDir), s)
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
	now := time.Now().UTC().Format(time.RFC3339)
	entry.SyncedAt = now
	for i := range s.Skills {
		if s.Skills[i].Name == entry.Name && s.Skills[i].Agent == entry.Agent {
			if s.Skills[i].FirstSeenAt != "" {
				entry.FirstSeenAt = s.Skills[i].FirstSeenAt
			}
			s.Skills[i] = entry
			return
		}
	}
	if entry.FirstSeenAt == "" {
		entry.FirstSeenAt = now
	}
	s.Skills = append(s.Skills, entry)
}

// Discover 记下「我看到了它」，但不一定收编。
func (s *State) Discover(name, agentName string) Entry {
	if existing, ok := s.Find(name, agentName); ok {
		return existing
	}
	now := time.Now().UTC().Format(time.RFC3339)
	s.Skills = append(s.Skills, Entry{
		Name: name, Agent: agentName, FirstSeenAt: now,
	})
	return Entry{Name: name, Agent: agentName, FirstSeenAt: now}
}

// New 是不是这次刚发现的（从没同步过）。
func (s State) New(name, agentName string) bool {
	entry, ok := s.Find(name, agentName)
	return ok && entry.BaseHash == ""
}

// Remove 去掉一条基线。
//
// 云端把这个 Skill 删掉时用：留着它，下一轮同步会把它当成
// 「云端没有、本地有」，把删掉的东西又传回云端。
func (s *State) Remove(name, agentName string) {
	for i := range s.Skills {
		if s.Skills[i].Name == name && s.Skills[i].Agent == agentName {
			s.Skills = append(s.Skills[:i], s.Skills[i+1:]...)
			return
		}
	}
}
