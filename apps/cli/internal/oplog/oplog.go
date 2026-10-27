// Package oplog 记录本机的 Skill 操作日志。
//
// 服务端的审计流水记的是「谁对哪个 Skill 做了什么」，权威、可查、跨设备；
// 这里记的是「这台机器上发生过什么」：同步把本地那份删了、装到了哪个目录、
// 哪一步失败了。同步删除、回收站搬文件这类只有本机看得见的细节，服务端那边
// 未必有——出问题时先看这份日志。
//
// 格式一行一条 JSON：机器读得动（jq、grep 都行），人也能直接看。
package oplog

import (
	"bufio"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// FileName 是日志文件名。
const FileName = "operations.log"

// Action 是操作的动作名。与服务端的审计动作名保持同一套说法，
// 两边对不上时排查会很痛苦。
const (
	ActionSyncUpload   = "sync.uploaded"
	ActionSyncDownload = "sync.downloaded"
	ActionSyncDeleted  = "sync.local_deleted"
	ActionSyncConflict = "sync.conflicted"
	ActionSyncFailed   = "sync.failed"
	ActionPublish      = "skill.published"
	ActionInstall      = "skill.installed"
	ActionUninstall    = "skill.uninstalled"
	ActionCloudDelete  = "skill.archived"
	ActionCloudRestore = "skill.restored"
)

// Event 是一条操作记录。
type Event struct {
	Time    string            `json:"time"`
	Action  string            `json:"action"`
	Skill   string            `json:"skill,omitempty"`
	Outcome string            `json:"outcome"`
	Detail  string            `json:"detail,omitempty"`
	Extra   map[string]string `json:"extra,omitempty"`
}

// Writer 往日志文件追加记录。
type Writer struct {
	path string
}

// New 返回写日志的 Writer。path 为空串表示不记（没有日志位置时不该因此失败）。
func New(path string) *Writer {
	return &Writer{path: path}
}

// Path 返回日志文件路径。
func (w *Writer) Path() string {
	if w == nil {
		return ""
	}
	return w.path
}

// Append 追加一条记录。
//
// 写不了就放弃：日志是辅助，不能让它把正事带崩。
func (w *Writer) Append(event Event) {
	if w == nil || w.path == "" {
		return
	}
	if event.Time == "" {
		event.Time = time.Now().UTC().Format(time.RFC3339)
	}
	if event.Outcome == "" {
		event.Outcome = "success"
	}
	raw, err := json.Marshal(event)
	if err != nil {
		return
	}
	if err := os.MkdirAll(filepath.Dir(w.path), 0o755); err != nil {
		return
	}
	file, err := os.OpenFile(w.path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return
	}
	defer file.Close()
	_, _ = file.Write(append(raw, '\n'))
}

// Record 记一条成功的操作。
func (w *Writer) Record(action, skill, detail string, extra map[string]string) {
	w.Append(Event{Action: action, Skill: skill, Outcome: "success",
		Detail: detail, Extra: extra})
}

// RecordFailure 记一次失败。
func (w *Writer) RecordFailure(action, skill, detail string) {
	w.Append(Event{Action: action, Skill: skill, Outcome: "failure", Detail: detail})
}

// Entry 是读回来的一条日志。
type Entry struct {
	Time    string            `json:"time"`
	Action  string            `json:"action"`
	Skill   string            `json:"skill,omitempty"`
	Outcome string            `json:"outcome"`
	Detail  string            `json:"detail,omitempty"`
	Extra   map[string]string `json:"extra,omitempty"`
}

// Tail 读日志的最后 n 条（最新的在最后）。
//
// skill 非空时只留那个 Skill 的记录。读不出来就返回空——日志坏了不该让
// 查看命令也跟着失败。
func Tail(path string, n int, skill string) []Entry {
	if n <= 0 {
		n = 50
	}
	file, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer file.Close()
	var all []Entry
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		var entry Entry
		if err := json.Unmarshal([]byte(line), &entry); err != nil {
			continue
		}
		if skill != "" && !strings.EqualFold(entry.Skill, skill) {
			continue
		}
		all = append(all, entry)
	}
	if len(all) > n {
		all = all[len(all)-n:]
	}
	return all
}
