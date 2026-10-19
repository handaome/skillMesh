// Package syncqueue 保存同步失败的任务，等网络恢复后重试
// （设计文档 §6.2、§13：网络断开时保留本地工作与队列，恢复后重试）。
package syncqueue

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"time"

	"gopkg.in/yaml.v3"
)

const FileName = "sync-queue.yaml"

// MaxAttempts 是放弃前的重试次数。
const MaxAttempts = 5

// Entry 是一条待重试的同步任务。
type Entry struct {
	Name          string `yaml:"name"`
	Agent         string `yaml:"agent"`
	Path          string `yaml:"path"`
	Attempts      int    `yaml:"attempts"`
	LastError     string `yaml:"last_error,omitempty"`
	NextAttemptAt string `yaml:"next_attempt_at,omitempty"`
	FirstSeenAt   string `yaml:"first_seen_at,omitempty"`
	GaveUp        bool   `yaml:"gave_up,omitempty"`
}

// Abandoned 表示这条已经重试到上限。
func (e Entry) Abandoned() bool { return e.GaveUp || e.Attempts >= MaxAttempts }

type Queue struct {
	UpdatedAt string  `yaml:"updated_at"`
	Entries   []Entry `yaml:"entries"`
}

func Path(configDir string) string {
	return filepath.Join(configDir, FileName)
}

func Load(configDir string) (Queue, error) {
	var queue Queue
	data, err := os.ReadFile(Path(configDir))
	if os.IsNotExist(err) {
		return Queue{}, nil
	}
	if err != nil {
		return queue, err
	}
	if err := yaml.Unmarshal(data, &queue); err != nil {
		return queue, fmt.Errorf("解析 %s: %w", FileName, err)
	}
	return queue, nil
}

func (q Queue) Save(configDir string) error {
	q.UpdatedAt = time.Now().UTC().Format(time.RFC3339)
	sort.Slice(q.Entries, func(i, j int) bool {
		return q.Entries[i].NextAttemptAt < q.Entries[j].NextAttemptAt
	})
	data, err := yaml.Marshal(q)
	if err != nil {
		return err
	}
	return os.WriteFile(Path(configDir), data, 0o644)
}

// Record 记一次失败并排下一次重试时间。退避按次数翻倍，封顶 30 分钟。
func (q *Queue) Record(name, agentName, path, reason string) {
	for i := range q.Entries {
		if q.Entries[i].Name == name && q.Entries[i].Agent == agentName {
			q.Entries[i].Attempts++
			q.Entries[i].LastError = reason
			q.Entries[i].GaveUp = q.Entries[i].Attempts >= MaxAttempts
			q.Entries[i].NextAttemptAt = time.Now().Add(Backoff(q.Entries[i].Attempts)).UTC().Format(time.RFC3339)
			return
		}
	}
	entry := Entry{
		Name: name, Agent: agentName, Path: path, Attempts: 1,
		LastError:     reason,
		FirstSeenAt:   time.Now().UTC().Format(time.RFC3339),
		NextAttemptAt: time.Now().Add(Backoff(1)).UTC().Format(time.RFC3339),
	}
	q.Entries = append(q.Entries, entry)
}

// Backoff 是第 n 次失败后要等多久。1 分钟起步，翻倍，封顶 30 分钟。
func Backoff(attempts int) time.Duration {
	if attempts < 1 {
		attempts = 1
	}
	wait := time.Minute
	for i := 1; i < attempts && wait < 30*time.Minute; i++ {
		wait *= 2
	}
	if wait > 30*time.Minute {
		wait = 30 * time.Minute
	}
	return wait
}

// Clear 同步成功后把任务移出队列。
func (q *Queue) Clear(name, agentName string) {
	kept := q.Entries[:0]
	for _, entry := range q.Entries {
		if entry.Name == name && entry.Agent == agentName {
			continue
		}
		kept = append(kept, entry)
	}
	q.Entries = kept
}

// Ready 判断这条任务现在该不该被重试。
func (e Entry) Ready(now time.Time) bool {
	if e.NextAttemptAt == "" {
		return true
	}
	at, err := time.Parse(time.RFC3339, e.NextAttemptAt)
	return err != nil || !now.Before(at)
}

// Due 挑出到点该重试的任务。放弃的（重试到上限）不再自动重试，但仍留在文件里可查。
func (q Queue) Due(now time.Time) []Entry {
	var due []Entry
	for _, entry := range q.Entries {
		if entry.Abandoned() || !entry.Ready(now) {
			continue
		}
		due = append(due, entry)
	}
	return due
}

// Pending 返回还在队列里的全部任务，含已放弃的——诊断要看得见它们。
func (q Queue) Pending() []Entry { return q.Entries }
