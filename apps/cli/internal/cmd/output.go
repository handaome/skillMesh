package cmd

import (
	"encoding/json"
	"fmt"
	"os"

	"github.com/spf13/cobra"
)

// 全局开关。--json 让命令输出稳定 JSON，--quiet 压掉普通提示，
// --verbose 放开平时默认隐藏的完整绝对路径。
var (
	jsonOut bool
	quiet   bool
	verbose bool
)

// jsonAnnotation 是命令上标记「本命令支持 --json」的注解键。
const jsonAnnotation = "skillmesh/json"

// supportsJSON 声明这个命令在 --json 下会输出结构化结果。
func supportsJSON(cmd *cobra.Command) *cobra.Command {
	if cmd.Annotations == nil {
		cmd.Annotations = map[string]string{}
	}
	cmd.Annotations[jsonAnnotation] = "true"
	return cmd
}

// jsonEnabled 报告当前是不是机器可读模式。
func jsonEnabled() bool { return jsonOut }

// writeJSON 往 stdout 输出一个 JSON 值。
//
// 机器可读模式下 stdout 只能有 JSON：提示、进度、警告一律走 stderr。
func writeJSON(value any) error {
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	return encoder.Encode(value)
}

// jsonItems 保证集合字段编码成 [] 而不是 null。
//
// Go 的 nil 切片会编成 null，脚本里 `for (const x of items)` 遇到 null
// 会直接报错——空的也该是空数组。
func jsonItems[T any](items []T) []T {
	if items == nil {
		return []T{}
	}
	return items
}

// notice 往 stderr 写一行提示。
//
// 提示不因为 --json 就消失——出问题时人还是要看见——只是不再污染 stdout。
func notice(format string, args ...any) {
	if quiet {
		return
	}
	fmt.Fprintf(os.Stderr, format, args...)
}
