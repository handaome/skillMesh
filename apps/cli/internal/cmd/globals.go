package cmd

import "github.com/spf13/cobra"

// 全局参数：--agent / --scope / --dry-run。
//
// 挂在根命令上，任何子命令都能写，不必每条命令各记一遍。
// 各命令保留自己的默认值：用户没给 --agent 时，install 装到
// claude-code，scan/sync 用全部启用的 Agent。
var (
	globalAgents []string
	globalScope  string
	globalDryRun bool
)

// dryRunAnnotation 标记「这个命令认 --dry-run」。
const dryRunAnnotation = "skillmesh/dry-run"

// supportsDryRun 声明这个命令支持 --dry-run。
//
// 不声明的命令收到 --dry-run 会提示一句「照常执行」：
// 用户以为演练过、实际动了数据，比直接报错更坏。
func supportsDryRun(cmd *cobra.Command) *cobra.Command {
	if cmd.Annotations == nil {
		cmd.Annotations = map[string]string{}
	}
	cmd.Annotations[dryRunAnnotation] = "true"
	return cmd
}

// agentNamesOr 返回本次要用的 Agent：用户没给就用命令自己的默认。
func agentNamesOr(fallback ...string) []string {
	if len(globalAgents) > 0 {
		return globalAgents
	}
	return fallback
}

// scopeOr 返回本次要用的范围：用户没给就用命令自己的默认。
func scopeOr(fallback string) string {
	if globalScope != "" {
		return globalScope
	}
	return fallback
}
