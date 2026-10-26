// Package lockfile 读写项目级的 skillmesh.yaml / skillmesh.lock.yaml
// （详细设计 §3.10）。
package lockfile

import (
	"path/filepath"
	"time"

	"github.com/handaome/skillmesh/apps/cli/internal/statefile"
)

const (
	FileName     = "skillmesh.lock.yaml"
	ManifestName = "skillmesh.yaml"
)

type Lock struct {
	Version    int           `yaml:"version"`
	ResolvedAt string        `yaml:"resolved_at"`
	Skills     []LockedSkill `yaml:"skills"`
}

// LockedSkill 是一次解析的结果。CI 用 --locked 校验它没漂移。
type LockedSkill struct {
	Name           string   `yaml:"name"`
	SourceID       string   `yaml:"source_id,omitempty"`
	Repository     string   `yaml:"repository"`
	Provider       string   `yaml:"provider,omitempty"`
	Path           string   `yaml:"path"`
	RefType        string   `yaml:"ref_type"`
	Ref            string   `yaml:"ref,omitempty"`
	ResolvedCommit string   `yaml:"resolved_commit"`
	Version        string   `yaml:"version,omitempty"`
	SHA256         string   `yaml:"sha256,omitempty"`
	InstalledHash  string   `yaml:"installed_hash,omitempty"`
	Scope          string   `yaml:"scope,omitempty"`
	UpdatePolicy   string   `yaml:"update_policy,omitempty"`
	Agents         []string `yaml:"agents"`
	// TargetPath 是实际装到哪。记下来是因为 Agent 目录会变：
	// 改了 config 里的路径，按配置重算出来的位置与当初装的对不上，
	// 卸载就会卸错地方（设计文档 §9.1 要求锁文件记「目标路径」）。
	TargetPath string `yaml:"target_path,omitempty"`
	// License 是识别到的许可证及识别状态（设计文档 §7.1）。
	// 只能重新分发不等于一定能分发，识别结果只是线索。
	License string `yaml:"license,omitempty"`
}

func Path(projectRoot string) string {
	return filepath.Join(projectRoot, FileName)
}

func Load(projectRoot string) (Lock, error) {
	return statefile.Load(Path(projectRoot), FileName, Lock{Version: 1})
}

func (l Lock) Save(projectRoot string) error {
	l.Version = 1
	if l.ResolvedAt == "" {
		l.ResolvedAt = time.Now().UTC().Format(time.RFC3339)
	}
	return statefile.Save(Path(projectRoot), l)
}

// Find 按名字或注册中心 id 找一条锁记录。
//
// 两种键都得认：`install <skill-id> --locked` 手里只有注册中心 id，
// 而 `skillmesh update <name>` 手里只有名字——只按名字查，
// --locked 就成了一空开关（找不到记录就当没锁）。
func (l Lock) Find(name string) (LockedSkill, bool) {
	for _, skill := range l.Skills {
		if skill.Name == name || (skill.SourceID != "" && skill.SourceID == name) {
			return skill, true
		}
	}
	return LockedSkill{}, false
}

// FindBySourceID 按注册中心 id 找一条锁记录。
//
// 与 Find 的区别：只认注册中心 id，不认本地名字。手里只有注册中心 id
// 的调用方用它，免得把名字碰巧等于这个 id 的另一条记录认成它。
func (l Lock) FindBySourceID(sourceID string) (LockedSkill, bool) {
	for _, skill := range l.Skills {
		if skill.SourceID == sourceID {
			return skill, true
		}
	}
	return LockedSkill{}, false
}

// Upsert 按名字新增或替换一条记录。
func (l *Lock) Upsert(entry LockedSkill) {
	for i := range l.Skills {
		if l.Skills[i].Name == entry.Name {
			l.Skills[i] = entry
			return
		}
	}
	l.Skills = append(l.Skills, entry)
}

func (l *Lock) Remove(name string) {
	kept := l.Skills[:0]
	for _, skill := range l.Skills {
		if skill.Name != name {
			kept = append(kept, skill)
		}
	}
	l.Skills = kept
}
