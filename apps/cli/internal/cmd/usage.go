package cmd

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"time"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/usage"
	"github.com/spf13/cobra"
)

// newUsageCmd Skill 调用统计：上报与查询。
//
// 上报是给 Agent 用的（Claude Code 每次真调用了 Skill 就报一条），
// 查询是给人用的：哪个 Skill 真在用、用了多少次。
func newUsageCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "usage",
		Short: "Skill 调用统计：上报与查询",
		Long: "统计「哪个 Skill 真在用、用了多少次」。\n\n" +
			"push 上报一次调用（Agent 的 hook 触发），上报失败的会先存在本机，\n" +
			"flush 把它们补报上去；stats 看聚合统计，list 看调用流水。",
	}
	cmd.AddCommand(newUsagePushCmd(), newUsageFlushCmd(),
		newUsageStatsCmd(), newUsageListCmd(), newUsageWatchCmd(),
		newUsageHookCmd())
	return cmd
}

// 服务端对上报字段的长度上限。超了会被判为校验失败，
// 补报也永远补不上去——所以超长就在本地截掉。
const (
	maxSkillNameLen   = 128
	maxEventIDLen     = 64
	maxProjectPathLen = 512
	maxUsageArgsLen   = 2000
	maxAgentNameLen   = 64
)

// newUsagePushCmd 上报一次 Skill 调用。
func newUsagePushCmd() *cobra.Command {
	var skill, argsText, projectPath, agentName, eventID string
	cmd := supportsJSON(&cobra.Command{
		Use:   "push",
		Short: "上报一次 Skill 调用",
		Long: "把「刚刚真调用了某个 Skill」记到云端。\n\n" +
			"上报失败不丢：这条会存进本机队列，等网络好了用 usage flush 补报。",
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			input, err := buildUsageInput(skill, argsText, projectPath, agentName, eventID)
			if err != nil {
				return err
			}
			client, err := newClient(cmd.Context())
			if err != nil {
				return reportOrQueue(input, err)
			}
			if err := client.ReportSkillUsage(cmd.Context(), input); err != nil {
				return reportOrQueue(input, err)
			}
			if jsonEnabled() {
				return writeJSON(map[string]any{
					"skill":    input.Skill,
					"event_id": input.EventID,
					"reported": true,
				})
			}
			fmt.Printf("已上报 %s\n", input.Skill)
			return nil
		},
	})
	cmd.Flags().StringVar(&skill, "skill", "", "被调用的 Skill 名")
	cmd.Flags().StringVar(&argsText, "args", "", "调用参数摘要")
	// --project / --agent 刻意盖掉全局的那两个：这里说的是「这次调用发生在哪」、
	// 「谁调的」，不是「本次命令的项目根目录 / 目标 Agent」。
	cmd.Flags().StringVar(&projectPath, "project", "", "这次调用发生在哪个项目目录（默认当前目录）")
	cmd.Flags().StringVar(&agentName, "agent", "claude-code", "调用方，如 claude-code")
	cmd.Flags().StringVar(&eventID, "event-id", "", "这次调用的唯一标识（默认随机生成）")
	return cmd
}

// buildUsageInput 把 flag 收成一条上报请求。
//
// 超长的字段在这里截掉：服务端按长度校验，超了会判校验失败，
// 这样的记录进重试队列也永远补不上去。
func buildUsageInput(skill, argsText, projectPath, agentName, eventID string) (apiclient.SkillUsageInput, error) {
	skill, err := checkSkillName(skill)
	if err != nil {
		return apiclient.SkillUsageInput{}, err
	}
	eventID, err = checkEventID(eventID)
	if err != nil {
		return apiclient.SkillUsageInput{}, err
	}
	if projectPath == "" {
		projectPath = mustGetwd()
	}
	return apiclient.SkillUsageInput{
		Skill:   skill,
		EventID: eventID,
		Project: truncate(projectPath, maxProjectPathLen),
		Args:    truncate(argsText, maxUsageArgsLen),
		Agent:   truncate(strings.TrimSpace(agentName), maxAgentNameLen),
	}, nil
}

// checkSkillName 校验被调用的 Skill 名。
//
// 名是统计的分组键，截短了就认不出来了，只能报错。
func checkSkillName(skill string) (string, error) {
	skill = strings.TrimSpace(skill)
	if skill == "" {
		return "", fmt.Errorf("--skill 要写被调用的 Skill 名")
	}
	if len([]rune(skill)) > maxSkillNameLen {
		return "", fmt.Errorf("--skill 太长了（%d 字），服务端最多收 %d 字",
			len([]rune(skill)), maxSkillNameLen)
	}
	return skill, nil
}

// checkEventID 校验幂等键；没给就随机生成一个。
func checkEventID(eventID string) (string, error) {
	if eventID == "" {
		generated, err := randomEventID()
		if err != nil {
			return "", fmt.Errorf("生成 event_id 失败: %w", err)
		}
		return generated, nil
	}
	if len([]rune(eventID)) > maxEventIDLen {
		return "", fmt.Errorf("--event-id 太长了（%d 字），服务端最多收 %d 字",
			len([]rune(eventID)), maxEventIDLen)
	}
	return eventID, nil
}

// reportOrQueue 上报失败时把这条记进本机队列，并返回带说明的错误。
//
// 返回错误而不是静默吞掉：调用方（hook 脚本）要知道这条没报上去。
// 错误里包着原始错误，退出码仍按原始错误分类（网络、鉴权、校验各归各的）。
func reportOrQueue(input apiclient.SkillUsageInput, cause error) error {
	if err := usage.Append(Cfg.CacheDir, input); err != nil {
		return fmt.Errorf("上报失败，存本地待重试也失败了（%v）: %w", err, cause)
	}
	return fmt.Errorf("上报失败，已存本地待重试（用 skillmesh usage flush 补报）: %w", cause)
}

// randomEventID 生成一次调用的幂等键。
//
// 用随机值而不是时间戳：同一秒里的两次调用不能撞成一条。
func randomEventID() (string, error) {
	buf := make([]byte, 16)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf), nil
}

// newUsageFlushCmd 把本机待重试的调用补报上去。
func newUsageFlushCmd() *cobra.Command {
	return supportsJSON(&cobra.Command{
		Use:   "flush",
		Short: "把本机待重试的调用补报上去",
		Long: "把上次上报失败、存在本机的调用记录重新推给云端。\n" +
			"报成功的从队列里去掉，没报成的留在队列里等下次。",
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			pending, err := usage.Load(Cfg.CacheDir)
			if err != nil {
				return err
			}
			if len(pending) == 0 {
				if jsonEnabled() {
					return writeJSON(map[string]any{"sent": 0, "failed": 0})
				}
				fmt.Println("没有待重试的调用记录。")
				return nil
			}
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			sent, remaining, failures := flushPending(cmd.Context(), client, pending)
			if err := usage.Replace(Cfg.CacheDir, remaining); err != nil {
				return err
			}
			failed := len(pending) - sent
			if jsonEnabled() {
				if err := writeJSON(map[string]any{"sent": sent, "failed": failed}); err != nil {
					return err
				}
			} else {
				fmt.Printf("补报成功 %d 条，失败 %d 条\n", sent, failed)
			}
			if failed > 0 {
				return fmt.Errorf("有 %d 条没补报成功，仍留在本地队列里: %w",
					failed, errors.Join(failures...))
			}
			return nil
		},
	})
}

// flushPending 逐条补报，返回报成功几条、还剩哪些没报成、每条的失败原因。
//
// 一条失败不打断后面的：一条都补不上去，整批重试就白跑了。
func flushPending(ctx context.Context, client *apiclient.Client,
	pending []apiclient.SkillUsageInput) (int, []apiclient.SkillUsageInput, []error) {

	remaining := make([]apiclient.SkillUsageInput, 0, len(pending))
	failures := make([]error, 0, len(pending))
	sent := 0
	for _, item := range pending {
		if err := client.ReportSkillUsage(ctx, item); err != nil {
			failures = append(failures, err)
			remaining = append(remaining, item)
			notice("补报 %s 失败：%v\n", item.Skill, err)
			continue
		}
		sent++
	}
	return sent, remaining, failures
}

// newUsageStatsCmd 看聚合统计。
func newUsageStatsCmd() *cobra.Command {
	var days int
	var skill string
	cmd := supportsJSON(&cobra.Command{
		Use:   "stats",
		Short: "看各 Skill 被调用了多少次",
		Long: "按 Skill 聚合的调用次数、用过的人数与最近一次调用时间。\n" +
			"--skill 按名字前缀过滤。",
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			rows, err := client.SkillUsageStats(cmd.Context(), days, skill)
			if err != nil {
				return err
			}
			if jsonEnabled() {
				return writeJSON(map[string]any{"items": jsonItems(rows)})
			}
			return printUsageStatTable(rows)
		},
	})
	cmd.Flags().IntVar(&days, "days", 30, "统计最近多少天")
	cmd.Flags().StringVar(&skill, "skill", "", "只看名字以此开头的 Skill")
	return cmd
}

// newUsageListCmd 看调用流水。
func newUsageListCmd() *cobra.Command {
	var limit int
	var cursor string
	cmd := supportsJSON(&cobra.Command{
		Use:   "list",
		Short: "看最近一次调用的流水",
		Long: "按时间倒序列出每次调用：什么时候、调了哪个 Skill、在哪个项目。\n" +
			"--cursor 用上一页返回的游标翻页。",
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			page, err := client.SkillUsageEvents(cmd.Context(), limit, cursor)
			if err != nil {
				return err
			}
			if jsonEnabled() {
				return writeJSON(map[string]any{
					"items":       jsonItems(page.Items),
					"next_cursor": page.NextCursor,
					"has_more":    page.HasMore,
				})
			}
			return printUsageEventTable(page)
		},
	})
	cmd.Flags().IntVar(&limit, "limit", 50, "返回条数")
	cmd.Flags().StringVar(&cursor, "cursor", "", "翻页游标：上一页返回的 next_cursor")
	return cmd
}

// printUsageStatTable 打印人读表格。
func printUsageStatTable(rows []apiclient.SkillUsageStat) error {
	if len(rows) == 0 {
		fmt.Println("还没有调用记录。")
		return nil
	}
	w := newTable(os.Stdout)
	fmt.Fprintln(w, "SKILL\t调用次数\t用户数\t最近调用")
	for _, row := range rows {
		fmt.Fprintf(w, "%s\t%d\t%d\t%s\n",
			row.Skill, row.CallCount, row.UserCount, orDash(row.LastUsedAt))
	}
	return w.Flush()
}

// printUsageEventTable 打印人读表格。
func printUsageEventTable(page apiclient.SkillUsagePage) error {
	if len(page.Items) == 0 {
		fmt.Println("还没有调用记录。")
		return nil
	}
	w := newTable(os.Stdout)
	fmt.Fprintln(w, "时间\tSKILL\t项目\t参数\tAGENT")
	for _, item := range page.Items {
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\n",
			item.OccurredAt, item.Skill, orDash(item.Project),
			orDash(item.ArgsPreview), orDash(item.Agent))
	}
	if err := w.Flush(); err != nil {
		return err
	}
	if page.HasMore && page.NextCursor != nil {
		fmt.Printf("还有更多：用 --cursor %s 翻下一页\n", *page.NextCursor)
	}
	return nil
}

// newUsageWatchCmd 盯着本机的调用日志，有新记录就上报。
//
// Agent 的 hook 只往日志里追加一行，不联网：发不发、发给谁、带哪些字段，
// 由这个命令说了算，改动在仓库里看得见、也改得动。
func newUsageWatchCmd() *cobra.Command {
	var interval time.Duration
	var logPath string
	var once bool
	cmd := supportsJSON(&cobra.Command{
		Use:   "watch",
		Short: "盯着本机的调用日志，有新记录就上报",
		Long: "轮询 Claude Code 的调用日志（默认 ~/.claude/skill-usage/usage.jsonl），\n" +
			"读到新记录就推给云端。推失败的记录留在日志里，下一轮再推。\n\n" +
			"--once 扫一遍就退出（用于补录历史）。Ctrl+C 退出常驻模式。",
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			path := logPath
			if path == "" {
				path = usage.LogPath()
			}
			if path == "" {
				return fmt.Errorf("找不到调用日志目录，用 --log 指定日志路径")
			}
			offset, err := usage.LoadCursor(Cfg.CacheDir)
			if err != nil {
				return err
			}
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			ctx, stop := signal.NotifyContext(cmd.Context(), os.Interrupt)
			defer stop()
			notice("盯着 %s，每 %s 查一次\n", path, interval)
			for {
				sent, err := reportNewCalls(ctx, client, path, &offset)
				if err != nil {
					notice("上报失败：%v\n", err)
				} else if sent > 0 {
					notice("上报 %d 条\n", sent)
				}
				if once {
					return nil
				}
				select {
				case <-ctx.Done():
					return nil
				case <-time.After(interval):
				}
			}
		},
	})
	cmd.Flags().DurationVar(&interval, "interval", 30*time.Second, "轮询间隔")
	cmd.Flags().StringVar(&logPath, "log", "", "调用日志路径（默认 ~/.claude/skill-usage/usage.jsonl）")
	cmd.Flags().BoolVar(&once, "once", false, "只扫一遍就退出，用于补录历史")
	return cmd
}

// reportNewCalls 把日志里新增的记录推给云端，返回推成功几条。
//
// 游标只在全部推成功时前进：推失败的留在日志里，下一轮再推。
// 重复推送不要紧——服务端按 event_id 去重，一次调用不会被算成两次。
func reportNewCalls(ctx context.Context, client *apiclient.Client,
	path string, offset *int64) (int, error) {

	records, next, err := usage.ReadNew(path, *offset)
	if err != nil {
		return 0, err
	}
	if len(records) == 0 {
		return 0, nil
	}
	sent := 0
	for _, item := range records {
		if err := client.ReportSkillUsage(ctx, item); err != nil {
			notice("上报 %s 失败：%v\n", item.Skill, err)
			continue
		}
		sent++
	}
	if sent == len(records) {
		*offset = next
		if err := usage.SaveCursor(Cfg.CacheDir, next); err != nil {
			return sent, err
		}
	}
	return sent, nil
}

// newUsageHookCmd 装/卸 Claude Code 的调用统计 hook。
//
// 换机器时不用到处找脚本：装好 CLI、登录，再跑一次 install 就行。
func newUsageHookCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "hook",
		Short: "安装或卸载 Claude Code 的调用统计 hook",
		Long: "把上报用的 hook 装进 Claude Code：\n\n" +
			"install 写入 ~/.claude/tools/skill-usage.cjs 并注册到 ~/.claude/settings.json，\n" +
			"之后每次真调用了 Skill 都会自动记录并上报。\n\n" +
			"uninstall 撤掉注册并删掉脚本。\n\n" +
			"装完要重启 Claude Code 会话才生效。",
	}
	cmd.AddCommand(newUsageHookInstallCmd(), newUsageHookUninstallCmd())
	return cmd
}

// newUsageHookInstallCmd 装上 hook。
func newUsageHookInstallCmd() *cobra.Command {
	return supportsJSON(&cobra.Command{
		Use:   "install",
		Short: "装上调用统计 hook",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			// 先把 CLI 发布到固定路径：hook 是被 Claude Code 拉起来的，
			// 那时 PATH 里未必有 skillmesh，得有个稳定路径找得到。
			if err := installSelf(); err != nil {
				return err
			}
			scriptPath, registered, err := usage.InstallHook(
				filepath.Join(cliDir(), selfInstallName()))
			if err != nil {
				return err
			}
			if jsonEnabled() {
				return writeJSON(map[string]any{
					"script":     scriptPath,
					"registered": registered,
				})
			}
			fmt.Printf("hook 脚本：%s\n", scriptPath)
			if registered {
				fmt.Println("已注册到 Claude Code 的 settings.json（PreToolUse -> Skill）")
			} else {
				fmt.Println("注册项已存在，未重复注册")
			}
			fmt.Println("重启 Claude Code 会话后生效。")
			return nil
		},
	})
}

// newUsageHookUninstallCmd 卸掉 hook。
func newUsageHookUninstallCmd() *cobra.Command {
	return supportsJSON(&cobra.Command{
		Use:   "uninstall",
		Short: "卸掉调用统计 hook",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			removed, err := usage.UninstallHook()
			if err != nil {
				return err
			}
			if jsonEnabled() {
				return writeJSON(map[string]any{"removed": removed})
			}
			if removed {
				fmt.Println("已撤掉注册并删除脚本")
			} else {
				fmt.Println("没有找到注册项，脚本已清理")
			}
			return nil
		},
	})
}
