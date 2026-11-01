package cmd

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/installer"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/oplog"
	"github.com/handaome/skillmesh/apps/cli/internal/redact"
	"github.com/handaome/skillmesh/apps/cli/internal/syncstate"
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

// targetPathOf 算出这个 Skill 在某个 Agent 里的实际安装路径。
//
// 优先用锁文件里记的路径：Agent 目录改了以后，按配置重算出来的位置与当初
// 装的对不上，卸载就会卸错地方（设计文档 §9.1）。
// 老锁文件没有这个字段，才退回按配置算。
func targetPathOf(agentName string, entry lockfile.LockedSkill, projectRoot string) (string, error) {
	if path := entry.TargetPathOf(agentName); path != "" {
		return path, nil
	}
	base, err := agentDirFor(agentName, entry, projectRoot)
	if err != nil {
		return "", err
	}
	return filepath.Join(base, entry.Name), nil
}

// listEntry 是 list 的一行。数据来自本机锁文件与磁盘实态，云端没有对应结构。
type listEntry struct {
	Skill   string `json:"skill"`
	Agent   string `json:"agent"`
	Source  string `json:"source"`
	Version string `json:"version"`
	// Latest 是远端的新 commit，只在 --outdated 时有值。
	Latest  string `json:"latest,omitempty"`
	Path    string `json:"path"`
	Managed bool   `json:"managed"`
}

// listSources 是 list --source 允许的取值。
var listSources = []string{"git", "cloud", "local"}

func newListCmd() *cobra.Command {
	var source string
	var outdated bool
	cmd := supportsJSON(&cobra.Command{
		Use:   "list",
		Short: "列出本项目已登记的本地安装",
		Long: "列出本项目锁文件里登记的本地安装。\n" +
			"注意这是「本地装了什么」，不是「云端有什么」——\n" +
			"发布到云端的 Skill 用 skillmesh search 查。",
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if source != "" && !contains(listSources, source) {
				return fmt.Errorf("--source 只能是 %s，收到 %q",
					strings.Join(listSources, " / "), source)
			}
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			projectRoot := projectRootDir()
			lock, err := lockfile.Load(projectRoot)
			if err != nil {
				return err
			}
			// --outdated：查一遍远端 ref，只留下有新版的那些。
			// 查的是 git ls-remote，不下载内容（设计文档 §3.7）。
			latest := map[string]string{}
			if outdated {
				updates, err := CheckUpdates(cmd.Context(), lock)
				if err != nil {
					return err
				}
				for _, update := range updates {
					latest[update.Name] = update.RemoteCommit
				}
			}
			entries := listEntries(lock, projectRoot, source, latest)
			if jsonEnabled() {
				if err := writeJSON(map[string]any{"items": entries}); err != nil {
					return err
				}
				warnRevoked(cmd.Context(), client, lock)
				return nil
			}
			if len(entries) == 0 {
				if outdated {
					fmt.Println("没有可更新的 Skill。")
					return nil
				}
				fmt.Println("本项目尚未登记任何本地安装。")
				fmt.Println("已发布到云端的 Skill 用 skillmesh search 查。")
				return nil
			}
			if err := printListTable(entries, outdated); err != nil {
				return err
			}
			warnRevoked(cmd.Context(), client, lock)
			return nil
		},
	})
	cmd.Flags().StringVar(&source, "source", "", "按来源过滤：git / cloud / local")
	cmd.Flags().BoolVar(&outdated, "outdated", false, "只列出远端有新版的 Skill")
	return cmd
}

// listEntries 把锁文件展开成一行一个 Agent 的安装记录。
//
// 路径默认脱敏：完整绝对路径会泄露本机目录布局，列表是要被贴到 issue 里的
// （详细设计 §3.9）。要完整路径加 --verbose。
//
// source 非空时只留该来源的；latest 里有的会带上远端的新 commit（--outdated）。
func listEntries(lock lockfile.Lock, projectRoot, source string,
	latest map[string]string) []listEntry {

	entries := make([]listEntry, 0, len(lock.Skills))
	for _, skill := range lock.Skills {
		kind := sourceOfEntry(skill)
		if source != "" && kind != source {
			continue
		}
		remote, hasRemote := latest[skill.Name]
		if len(latest) > 0 && !hasRemote {
			continue // --outdated：只列有新版的
		}
		for _, agentName := range skill.Agents {
			target, err := targetPathOf(agentName, skill, projectRoot)
			if err != nil {
				target = "?"
			}
			shown := target
			if !verbose {
				shown = redact.Path(target)
			}
			entry := listEntry{
				Skill:   skill.Name,
				Agent:   agentName,
				Source:  kind,
				Version: ResolvedLabel(skill),
				Path:    shown,
				Managed: installer.IsManaged(target),
			}
			if hasRemote {
				entry.Latest = short(remote)
			}
			entries = append(entries, entry)
		}
	}
	return entries
}

// sourceOfEntry 说这条安装记录的来源类型。
//
// Git 来源有仓库地址；本地目录来源标了 local；其余是注册中心装来的。
func sourceOfEntry(entry lockfile.LockedSkill) string {
	switch {
	case entry.Repository != "" || entry.SourceType == "git":
		return "git"
	case entry.SourceType == "local":
		return "local"
	default:
		return "cloud"
	}
}

// printListTable 打印人读表格。
func printListTable(entries []listEntry, outdated bool) error {
	w := newTable(os.Stdout)
	if outdated {
		fmt.Fprintln(w, "SKILL\tAGENT\t来源\t本地 commit\t新 commit\t路径\t受管")
	} else {
		fmt.Fprintln(w, "SKILL\tAGENT\t来源\t版本/commit\t路径\t受管")
	}
	for _, entry := range entries {
		managed := "否"
		if entry.Managed {
			managed = "是"
		}
		if outdated {
			fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\t%s\t%s\n",
				entry.Skill, entry.Agent, entry.Source, entry.Version,
				entry.Latest, entry.Path, managed)
			continue
		}
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\t%s\n",
			entry.Skill, entry.Agent, entry.Source, entry.Version, entry.Path, managed)
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

func newUninstallCmd() *cobra.Command {
	var keepFiles, purgeCache bool
	var force, purge bool
	cmd := &cobra.Command{
		Use:   "uninstall <skill-name>",
		Short: "移除安装内容与锁文件记录",
		Long: "卸载只动本机：注销安装记录，必要时移走本地文件。\n" +
			"云端的 Skill 不受影响——删云端要用 skillmesh cloud delete。",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			projectRoot := projectRootDir()
			lock, err := lockfile.Load(projectRoot)
			if err != nil {
				return err
			}
			entry, ok := lock.Find(args[0])
			if !ok {
				return fmt.Errorf("锁文件中没有 %s", args[0])
			}
			for _, agentName := range entry.Agents {
				target, err := targetPathOf(agentName, entry, projectRoot)
				if err != nil {
					return err
				}
				if keepFiles {
					// 保留文件：只注销记录，目录原样留着。
					fmt.Printf("保留 %s\n", target)
				} else if err := uninstallTarget(entry.Name, target, force || purge); err != nil {
					return err
				}
				if purgeCache {
					purgeLocalCache(entry.Name, agentName)
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
	cmd.Flags().BoolVar(&keepFiles, "keep-files", false,
		"保留本地文件，只注销安装记录")
	cmd.Flags().BoolVar(&purgeCache, "purge-cache", false,
		"卸载后清理本机缓存（这个 Skill 的同步基线与快照）")
	// --force / --purge 是旧名字：语义都是「直接删，不进回收站」。
	// 留着只为兼容旧脚本，帮助里标成废弃。
	cmd.Flags().BoolVar(&force, "force", false,
		"直接删除，不进回收站（不受管理的目录也删；已废弃，用 --keep-files/--purge-cache）")
	cmd.Flags().BoolVar(&purge, "purge", false,
		"直接删除，不进回收站（已废弃，等同 --force）")
	_ = cmd.Flags().MarkDeprecated("force", "用 --keep-files 保留文件，或默认进回收站")
	_ = cmd.Flags().MarkDeprecated("purge", "用 --keep-files 保留文件，或默认进回收站")
	return cmd
}

// uninstallTarget 按模式处理一个目标目录：真删、强删，或移进回收站。
//
// 默认进回收站：卸载还能找回来（设计文档 §6.4、§15.1）。
//
// 目录不受 SkillMesh 管理时不删——那多半是用户自己换上去的，
// 删掉等于替用户做决定（设计文档 §3.9「防止误删用户自行替换的目录」）。
func uninstallTarget(name, target string, force bool) error {
	if !force && !installer.IsManaged(target) {
		if _, err := os.Stat(target); err != nil {
			return nil // 已经不在了，没什么可删的
		}
		fmt.Printf("保留 %s —— 目录不是 SkillMesh 装的，可能你换过它\n", target)
		return nil
	}
	if force {
		// --force 是真删：不可恢复，只在明确要求时走
		if err := os.RemoveAll(target); err != nil {
			return err
		}
		fmt.Printf("已移除 %s\n", target)
		return nil
	}
	item, err := trash.NewStore(Cfg.ConfigDir).Move(name, target)
	if err != nil {
		return err
	}
	fmt.Printf("已移到回收站 %s（%d KiB，skillmesh trash restore 可找回）\n",
		item.Entry, item.SizeKiB)
	return nil
}

// purgeLocalCache 清掉这个 Skill 在本机留下的缓存：同步基线与基线快照。
//
// 快照是「上次同步时双方都认可的那份内容」，留着它下次同步会拿旧基线
// 去对账——卸载了却还记着基线，是很难查的一类错。
func purgeLocalCache(name, agentName string) {
	state, err := syncstate.Load(Cfg.ConfigDir)
	if err == nil {
		state.Remove(name, agentName)
		_ = state.Save(Cfg.ConfigDir)
	}
	snapshot := filepath.Join(Cfg.ConfigDir, "sync-base", agentName, name)
	if err := os.RemoveAll(snapshot); err == nil {
		fmt.Printf("已清理本机缓存 %s\n", snapshot)
	}
}
