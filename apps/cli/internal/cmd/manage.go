package cmd

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/installer"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/oplog"
	"github.com/handaome/skillmesh/apps/cli/internal/redact"
	"github.com/handaome/skillmesh/apps/cli/internal/trash"
	"github.com/spf13/cobra"
)

// scopeOf 决定这次操作的作用域。锁文件里记了 scope 就以记录为准——
// add 装到项目级、update 却写回用户目录，是很难查的一类错（设计文档 §8）。
// 老锁文件没有这个字段，按有没有 skillmesh.yaml 兜底。
func scopeOf(scope, projectRoot string) string {
	if scope != "" {
		return scope
	}
	if _, err := os.Stat(filepath.Join(projectRoot, lockfile.ManifestName)); err == nil {
		return "project"
	}
	return "user"
}

// agentDirFor 解析某个 Agent 在当前项目下的安装目录。
func agentDirFor(name string, entry lockfile.LockedSkill, projectRoot string) (string, error) {
	return agent.Dir(Cfg, name, scopeOf(entry.Scope, projectRoot), projectRoot)
}

// listEntry 是 list 的一行。数据来自本机锁文件与磁盘实态，云端没有对应结构。
type listEntry struct {
	Skill   string `json:"skill"`
	Agent   string `json:"agent"`
	Version string `json:"version"`
	Path    string `json:"path"`
	Managed bool   `json:"managed"`
}

func newListCmd() *cobra.Command {
	return supportsJSON(&cobra.Command{
		Use:   "list",
		Short: "列出本项目已登记的本地安装",
		Long: "列出本项目锁文件里登记的本地安装。\n" +
			"注意这是「本地装了什么」，不是「云端有什么」——\n" +
			"发布到云端的 Skill 用 skillmesh search 查。",
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			projectRoot := mustGetwd()
			lock, err := lockfile.Load(projectRoot)
			if err != nil {
				return err
			}
			entries := listEntries(lock, projectRoot)
			if jsonEnabled() {
				if err := writeJSON(map[string]any{"items": entries}); err != nil {
					return err
				}
				warnRevoked(cmd.Context(), client, lock)
				return nil
			}
			if len(entries) == 0 {
				fmt.Println("本项目尚未登记任何本地安装。")
				fmt.Println("已发布到云端的 Skill 用 skillmesh search 查。")
				return nil
			}
			if err := printListTable(entries); err != nil {
				return err
			}
			warnRevoked(cmd.Context(), client, lock)
			return nil
		},
	})
}

// listEntries 把锁文件展开成一行一个 Agent 的安装记录。
//
// 路径默认脱敏：完整绝对路径会泄露本机目录布局，列表是要被贴到 issue 里的
// （详细设计 §3.9）。要完整路径加 --verbose。
func listEntries(lock lockfile.Lock, projectRoot string) []listEntry {
	entries := make([]listEntry, 0, len(lock.Skills))
	for _, skill := range lock.Skills {
		for _, agentName := range skill.Agents {
			base, err := agentDirFor(agentName, skill, projectRoot)
			if err != nil {
				base = "?"
			}
			target := filepath.Join(base, skill.Name)
			shown := target
			if !verbose {
				shown = redact.Path(target)
			}
			entries = append(entries, listEntry{
				Skill:   skill.Name,
				Agent:   agentName,
				Version: ResolvedLabel(skill),
				Path:    shown,
				Managed: installer.IsManaged(target),
			})
		}
	}
	return entries
}

// printListTable 打印人读表格。
func printListTable(entries []listEntry) error {
	w := newTable(os.Stdout)
	fmt.Fprintln(w, "SKILL\tAGENT\t版本/commit\t路径\t受管")
	for _, entry := range entries {
		managed := "否"
		if entry.Managed {
			managed = "是"
		}
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\n",
			entry.Skill, entry.Agent, entry.Version, entry.Path, managed)
	}
	return w.Flush()
}

// unregisterInstallations 把这个 Skill 在本机的安装记录标成已卸载。
func unregisterInstallations(ctx context.Context, client *apiclient.Client, skillID string) {
	rows, err := client.Installations(ctx, "")
	if err != nil {
		return
	}
	for _, row := range rows {
		if row.SkillID == skillID && row.Status == "active" {
			_ = client.UnregisterInstallation(ctx, row.ID)
		}
	}
}

// warnRevoked 提示「装着的版本已经被撤回」。
// 不自动卸载、不删本地文件——那是用户自己的机器（设计文档 §10 撤回）。
func warnRevoked(ctx context.Context, client *apiclient.Client, lock lockfile.Lock) {
	for _, entry := range lock.Skills {
		if entry.SourceID == "" || entry.Version == "" {
			continue
		}
		versions, err := client.Versions(ctx, entry.SourceID)
		if err != nil {
			continue // 查不到就当没事，别在 list 里制造噪音
		}
		status, found := VersionStatus(versions, entry.Version)
		if !found || status == "published" {
			continue
		}
		notice("注意：%s 当前装的 %s 状态是 %s，云端已不可用，请自行确认是否继续使用。\n",
			entry.Name, entry.Version, status)
	}
}

// ResolvedLabel 显示锁住的东西：Git 来源锁的是 commit，注册中心来源锁的是版本号。
func ResolvedLabel(skill lockfile.LockedSkill) string {
	if skill.ResolvedCommit != "" {
		return short(skill.ResolvedCommit)
	}
	return orDash(skill.Version)
}

func newStatusCmd() *cobra.Command {
	return supportsJSON(&cobra.Command{
		Use:   "status [skill-name]",
		Short: "查看锁文件里的安装状态（JSON）",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			lock, err := lockfile.Load(mustGetwd())
			if err != nil {
				return err
			}
			var payload any = map[string]any{
				"resolved_at": lock.ResolvedAt,
				"skills":      lock.Skills,
			}
			if len(args) == 1 {
				skill, ok := lock.Find(args[0])
				if !ok {
					return fmt.Errorf("锁文件中没有 %s", args[0])
				}
				payload = skill
			}
			encoder := json.NewEncoder(os.Stdout)
			encoder.SetIndent("", "  ")
			return encoder.Encode(payload)
		},
	})
}

func newUninstallCmd() *cobra.Command {
	var force, purge bool
	cmd := &cobra.Command{
		Use:   "uninstall <skill-name>",
		Short: "移除安装内容与锁文件记录",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			projectRoot := mustGetwd()
			lock, err := lockfile.Load(projectRoot)
			if err != nil {
				return err
			}
			entry, ok := lock.Find(args[0])
			if !ok {
				return fmt.Errorf("锁文件中没有 %s", args[0])
			}
			for _, agentName := range entry.Agents {
				base, err := agentDirFor(agentName, entry, projectRoot)
				if err != nil {
					return err
				}
				target := filepath.Join(base, entry.Name)
				if err := uninstallTarget(entry.Name, target, force, purge); err != nil {
					return err
				}
				opLog().Record(oplog.ActionUninstall, entry.Name, ResolvedLabel(entry),
					map[string]string{"agent": agentName, "path": target})
			}
			lock.Remove(entry.Name)
			if err := lock.Save(projectRoot); err != nil {
				return err
			}
			if client, err := newClient(cmd.Context()); err == nil && entry.SourceID != "" {
				_ = client.ReportAuditEvent(cmd.Context(), "uninstall.completed",
					entry.SourceID, "success", map[string]string{"name": entry.Name})
				unregisterInstallations(cmd.Context(), client, entry.SourceID)
			}
			return nil
		},
	}
	cmd.Flags().BoolVar(&force, "force", false, "连同不受 SkillMesh 管理的目录一起删除")
	cmd.Flags().BoolVar(&purge, "purge", false, "彻底删除，不进回收站（不可恢复）")
	return cmd
}

// uninstallTarget 按模式处理一个目标目录：真删、强删，或移进回收站。
//
// 默认进回收站：卸载还能找回来（设计文档 §6.4、§15.1）。
func uninstallTarget(name, target string, force, purge bool) error {
	switch {
	case purge:
		// --purge 是真删：不可恢复，只在明确要求时走
		if err := os.RemoveAll(target); err != nil {
			return err
		}
		fmt.Printf("已彻底删除 %s\n", target)
	case force:
		if err := os.RemoveAll(target); err != nil {
			return err
		}
		fmt.Printf("已移除 %s\n", target)
	default:
		item, err := trash.NewStore(Cfg.ConfigDir).Move(name, target)
		if err != nil {
			return err
		}
		fmt.Printf("已移到回收站 %s（%d KiB，skillmesh trash restore 可找回）\n",
			item.Entry, item.SizeKiB)
	}
	return nil
}
