package usage

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// hookScript 是随 CLI 分发的 hook 脚本。
//
// 装脚本这件事跟着 CLI 走：换一台机器只要装好 CLI、登录，再跑一次
// usage hook install 就行，不用到处找脚本文件。
//
//go:embed hook_script.cjs
var hookScript string

const (
	// hookScriptName 是脚本文件名。
	hookScriptName = "skill-usage.cjs"
	// hookMarker 用来在 settings.json 里认出自己那条注册。
	hookMarker = "skill-usage.cjs"
	// cliPathPlaceholder 是脚本里 CLI 路径的占位符。
	// 安装时换成 CLI 的真实路径，脚本就不依赖 PATH 了。
	cliPathPlaceholder = "__SKILLMESH_CLI_PATH__"
)

// ClaudeDir 返回 Claude Code 的配置目录（~/.claude）。
func ClaudeDir() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("找不到用户主目录: %w", err)
	}
	return filepath.Join(home, ".claude"), nil
}

// HookScriptPath 返回 hook 脚本的安装路径。
func HookScriptPath() (string, error) {
	dir, err := ClaudeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "tools", hookScriptName), nil
}

// SettingsPath 返回 Claude Code 的 settings.json 路径。
func SettingsPath() (string, error) {
	dir, err := ClaudeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "settings.json"), nil
}

// InstallHook 把脚本写进 ~/.claude/tools，并注册进 Claude Code 的 settings.json。
//
// cliPath 是脚本里用来调 CLI 的路径——通常是固定发布路径那份，
// 这样不依赖 PATH。
//
// 幂等：重复装只更新脚本内容，不重复注册。
// 返回的 registered 表示这次有没有新加注册项。
func InstallHook(cliPath string) (scriptPath string, registered bool, err error) {
	scriptPath, err = HookScriptPath()
	if err != nil {
		return "", false, err
	}
	if err := os.MkdirAll(filepath.Dir(scriptPath), 0o755); err != nil {
		return "", false, fmt.Errorf("建工具目录失败: %w", err)
	}
	if err := os.WriteFile(scriptPath, []byte(scriptWithCLIPath(cliPath)), 0o644); err != nil {
		return "", false, fmt.Errorf("写 hook 脚本失败: %w", err)
	}

	settingsPath, err := SettingsPath()
	if err != nil {
		return scriptPath, false, err
	}
	settings, err := loadSettings(settingsPath)
	if err != nil {
		return scriptPath, false, err
	}
	changed, err := registerHook(settings, scriptPath)
	if err != nil {
		return scriptPath, false, err
	}
	if changed {
		if err := saveSettings(settingsPath, settings); err != nil {
			return scriptPath, false, err
		}
	}
	return scriptPath, changed, nil
}

// UninstallHook 撤掉注册并删掉脚本。
//
// 返回的 removed 表示注册项是不是这次才撤掉的。
func UninstallHook() (removed bool, err error) {
	settingsPath, err := SettingsPath()
	if err != nil {
		return false, err
	}
	settings, err := loadSettings(settingsPath)
	if err != nil {
		return false, err
	}
	removed, err = unregisterHook(settings)
	if err != nil {
		return false, err
	}
	if removed {
		if err := saveSettings(settingsPath, settings); err != nil {
			return false, err
		}
	}
	if scriptPath, err := HookScriptPath(); err == nil {
		_ = os.Remove(scriptPath)
	}
	return removed, nil
}

// loadSettings 读 Claude Code 的 settings.json。
//
// 文件不存在是合法状态（还没配过），返回空对象。
func loadSettings(path string) (map[string]any, error) {
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return map[string]any{}, nil
	}
	if err != nil {
		return nil, fmt.Errorf("读 %s 失败: %w", path, err)
	}
	settings := map[string]any{}
	if err := json.Unmarshal(data, &settings); err != nil {
		return nil, fmt.Errorf("解析 %s 失败: %w", path, err)
	}
	return settings, nil
}

// saveSettings 写回 settings.json，写之前先备份一份。
//
// 改坏了还能退回上一份：这份文件里还有用户的其它配置，不能丢。
func saveSettings(path string, settings map[string]any) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return fmt.Errorf("建配置目录失败: %w", err)
	}
	if previous, err := os.ReadFile(path); err == nil {
		if err := os.WriteFile(path+".bak", previous, 0o644); err != nil {
			return fmt.Errorf("备份 %s 失败: %w", path, err)
		}
	}
	data, err := json.MarshalIndent(settings, "", "  ")
	if err != nil {
		return fmt.Errorf("序列化配置失败: %w", err)
	}
	if err := os.WriteFile(path, append(data, '\n'), 0o644); err != nil {
		return fmt.Errorf("写 %s 失败: %w", path, err)
	}
	return nil
}

// registerHook 把 PreToolUse 的 Skill 匹配加进 settings。
//
// 已经注册过就原样返回 false，不动别人的配置。
func registerHook(settings map[string]any, scriptPath string) (bool, error) {
	hooks, _ := settings["hooks"].(map[string]any)
	if hooks == nil {
		hooks = map[string]any{}
	}
	preToolUse, _ := hooks["PreToolUse"].([]any)
	for _, item := range preToolUse {
		if matcherHasHook(item, hookMarker) {
			return false, nil
		}
	}
	preToolUse = append(preToolUse, map[string]any{
		"matcher": "Skill",
		"hooks": []any{map[string]any{
			"type":    "command",
			"command": fmt.Sprintf("node %s hook", quotePath(scriptPath)),
		}},
	})
	hooks["PreToolUse"] = preToolUse
	settings["hooks"] = hooks
	return true, nil
}

// unregisterHook 从 settings 里去掉自己那条注册。
func unregisterHook(settings map[string]any) (bool, error) {
	hooks, _ := settings["hooks"].(map[string]any)
	if hooks == nil {
		return false, nil
	}
	preToolUse, _ := hooks["PreToolUse"].([]any)
	kept := make([]any, 0, len(preToolUse))
	removed := false
	for _, item := range preToolUse {
		if matcherHasHook(item, hookMarker) {
			removed = true
			continue
		}
		kept = append(kept, item)
	}
	if !removed {
		return false, nil
	}
	if len(kept) == 0 {
		delete(hooks, "PreToolUse")
	} else {
		hooks["PreToolUse"] = kept
	}
	if len(hooks) == 0 {
		delete(settings, "hooks")
	} else {
		settings["hooks"] = hooks
	}
	return true, nil
}

// matcherHasHook 判断一个匹配项里有没有含 marker 的命令。
func matcherHasHook(item any, marker string) bool {
	matcher, ok := item.(map[string]any)
	if !ok {
		return false
	}
	list, _ := matcher["hooks"].([]any)
	for _, entry := range list {
		hook, ok := entry.(map[string]any)
		if !ok {
			continue
		}
		if command, _ := hook["command"].(string); strings.Contains(command, marker) {
			return true
		}
	}
	return false
}

// quotePath 给命令里的路径加引号：路径里有空格时不加会被拆成两截。
func quotePath(p string) string {
	return `"` + p + `"`
}

// scriptWithCLIPath 把脚本里的 CLI 路径占位符换成真实路径。
func scriptWithCLIPath(cliPath string) string {
	return strings.Replace(hookScript, cliPathPlaceholder, jsString(cliPath), 1)
}

// jsString 把路径转成能安全放进 JS 单引号字符串的形式。
func jsString(p string) string {
	replacer := strings.NewReplacer(`\`, `\\`, `'`, `\'`)
	return replacer.Replace(p)
}
