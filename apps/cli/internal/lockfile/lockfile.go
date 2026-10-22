// Package lockfile 读写项目级的 skillmesh.yaml / skillmesh.lock.yaml
// （详细设计 §3.10）。
package lockfile

import (
	"fmt"
	"os"
	"path/filepath"
	"time"

	"gopkg.in/yaml.v3"
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
}

func Path(projectRoot string) string {
	return filepath.Join(projectRoot, FileName)
}

func Load(projectRoot string) (Lock, error) {
	var lock Lock
	data, err := os.ReadFile(Path(projectRoot))
	if os.IsNotExist(err) {
		return Lock{Version: 1}, nil
	}
	if err != nil {
		return lock, err
	}
	if err := yaml.Unmarshal(data, &lock); err != nil {
		return lock, fmt.Errorf("解析 %s: %w", FileName, err)
	}
	return lock, nil
}

func (l Lock) Save(projectRoot string) error {
	l.Version = 1
	if l.ResolvedAt == "" {
		l.ResolvedAt = time.Now().UTC().Format(time.RFC3339)
	}
	data, err := yaml.Marshal(l)
	if err != nil {
		return err
	}
	return os.WriteFile(Path(projectRoot), data, 0o644)
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
