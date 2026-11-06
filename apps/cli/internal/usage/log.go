package usage

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
)

// Claude Code 的调用日志：Agent 的 hook 每次真调用了 Skill 就往那儿追加一行。
//
// hook 自己不联网——它只写本地日志，由本包（进而由 CLI）读走并上报。
// 这样「谁把会话数据发到云端」这件事在仓库里看得见、改得动。

const (
	// LogFileName 是 hook 写的日志文件名。
	LogFileName = "usage.jsonl"
	// cursorFileName 记着上次报到日志的哪个位置了。
	cursorFileName = "usage-cursor.json"
)

// 服务端对上报字段的长度上限。超了服务端判校验失败，
// 这样的记录补报也永远补不上去——所以本地先截掉。
const (
	maxSkillNameLen   = 128
	maxEventIDLen     = 64
	maxProjectPathLen = 512
	maxUsageArgsLen   = 2000
)

// LogPath 返回 Claude Code 调用日志的路径。
//
// 默认在用户主目录的 .claude/skill-usage 下：日志跟着 Claude Code 走。
// 找不到主目录就返回空串，调用方跳过这一段。
func LogPath() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	return filepath.Join(home, ".claude", "skill-usage", LogFileName)
}

// CursorPath 返回游标文件路径。
func CursorPath(cacheDir string) string {
	return filepath.Join(cacheDir, cursorFileName)
}

// cursor 记着已上报到日志的哪个字节位置。
type cursor struct {
	Offset int64 `json:"offset"`
}

// LoadCursor 读游标；没有就是从头开始。
//
// 游标坏了也从头开始：宁可多报几次（服务端按 event_id 去重，不会重复计数），
// 也不能漏报。
func LoadCursor(cacheDir string) (int64, error) {
	data, err := os.ReadFile(CursorPath(cacheDir))
	if os.IsNotExist(err) {
		return 0, nil
	}
	if err != nil {
		return 0, fmt.Errorf("读上报游标失败: %w", err)
	}
	var c cursor
	if err := json.Unmarshal(data, &c); err != nil {
		return 0, nil
	}
	return c.Offset, nil
}

// SaveCursor 存游标。
func SaveCursor(cacheDir string, offset int64) error {
	path := CursorPath(cacheDir)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return fmt.Errorf("建缓存目录失败: %w", err)
	}
	data, err := json.Marshal(cursor{Offset: offset})
	if err != nil {
		return fmt.Errorf("序列化上报游标失败: %w", err)
	}
	if err := os.WriteFile(path, data, 0o644); err != nil {
		return fmt.Errorf("写上报游标失败: %w", err)
	}
	return nil
}

// logEntry 是 hook 写的一行。
type logEntry struct {
	Skill     string `json:"skill"`
	Args      string `json:"args"`
	CWD       string `json:"cwd"`
	ToolUseID string `json:"tool_use_id"`
	Timestamp string `json:"ts"`
}

// ReadNew 读日志里 offset 之后新增的记录，并给出新的 offset。
//
// 只读到最后一个完整行：hook 正在写时最后一行可能还没写完，
// 读半截解析不了，那条等下一轮再读。
func ReadNew(path string, offset int64) ([]apiclient.SkillUsageInput, int64, error) {
	file, err := os.Open(path)
	if os.IsNotExist(err) {
		return nil, offset, nil
	}
	if err != nil {
		return nil, offset, fmt.Errorf("打开调用日志失败: %w", err)
	}
	defer file.Close()

	info, err := file.Stat()
	if err != nil {
		return nil, offset, fmt.Errorf("读调用日志属性失败: %w", err)
	}
	if info.Size() <= offset {
		return nil, offset, nil
	}
	if _, err := file.Seek(offset, io.SeekStart); err != nil {
		return nil, offset, fmt.Errorf("定位调用日志失败: %w", err)
	}

	reader := bufio.NewReader(file)
	var records []apiclient.SkillUsageInput
	consumed := offset
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			// 最后一行还没写完：停在它前面，下轮再读。
			break
		}
		consumed += int64(len(line))
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			continue
		}
		var entry logEntry
		if err := json.Unmarshal([]byte(trimmed), &entry); err != nil {
			continue
		}
		if entry.Skill == "" {
			continue
		}
		records = append(records, apiclient.SkillUsageInput{
			Skill:   truncate(entry.Skill, maxSkillNameLen),
			EventID: eventIDOf(entry),
			Project: truncate(entry.CWD, maxProjectPathLen),
			Args:    truncate(entry.Args, maxUsageArgsLen),
			Agent:   "claude-code",
		})
	}
	return records, consumed, nil
}

// eventIDOf 用 hook 记下的工具调用 id 当幂等键。
//
// 一次调用一个 id，重读日志读到同一条时带的是同一个键——
// 服务端按它去重，重复上报不会把一次调用算成两次。
func eventIDOf(entry logEntry) string {
	if entry.ToolUseID != "" {
		return truncate(entry.ToolUseID, maxEventIDLen)
	}
	return truncate(entry.Timestamp+"-"+entry.Skill, maxEventIDLen)
}

// truncate 按字符截断，超长部分丢掉。
func truncate(value string, limit int) string {
	runes := []rune(value)
	if len(runes) <= limit {
		return value
	}
	return string(runes[:limit])
}
