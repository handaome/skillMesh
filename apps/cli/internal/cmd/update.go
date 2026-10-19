package cmd

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"text/tabwriter"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/gitx"
	"github.com/handaome/skillmesh/apps/cli/internal/installer"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/spf13/cobra"
)

// UpdateInfo 是一次检查更新的结果。
type UpdateInfo struct {
	Name         string
	Repository   string
	Path         string
	Ref          string
	Scope        string
	Agents       []string
	LocalCommit  string
	RemoteCommit string
}

// lsRemote 包一层是为了测试能替换它，统计同一个仓库被查了几次。
var lsRemote = gitx.LsRemote

// CheckUpdates 只调 git ls-remote，不下载内容（详细设计 §3.7）。
//
// 一个仓库里可能装了几十个 Skill（--all 装的），但远端 commit 只有一个：
// 所以按「仓库 + ref」缓存，每个仓库只查一次。
func CheckUpdates(ctx context.Context, lock lockfile.Lock) ([]UpdateInfo, error) {
	remoteCache := map[string]string{}
	var updates []UpdateInfo
	for _, skill := range lock.Skills {
		if skill.Repository == "" {
			continue
		}
		key := skill.Repository + "\x00" + skill.Ref
		remote, cached := remoteCache[key]
		if !cached {
			var err error
			remote, err = lsRemote(ctx, skill.Repository, skill.Ref)
			if err != nil {
				return nil, fmt.Errorf("检查 %s 失败: %w", skill.Name, err)
			}
			remoteCache[key] = remote
		}
		if remote != skill.ResolvedCommit {
			updates = append(updates, UpdateInfo{
				Name:         skill.Name,
				Repository:   skill.Repository,
				Path:         skill.Path,
				Ref:          skill.Ref,
				Scope:        skill.Scope,
				Agents:       skill.Agents,
				LocalCommit:  skill.ResolvedCommit,
				RemoteCommit: remote,
			})
		}
	}
	return updates, nil
}

func newUpdateCmd() *cobra.Command {
	var checkOnly, all, dryRun bool
	cmd := &cobra.Command{
		Use:   "update [skill-name]",
		Short: "检查或应用更新",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			projectRoot := mustGetwd()
			lock, err := lockfile.Load(projectRoot)
			if err != nil {
				return err
			}
			if len(args) == 1 {
				skill, ok := lock.Find(args[0])
				if !ok {
					return fmt.Errorf("锁文件中没有 %s", args[0])
				}
				lock = lockfile.Lock{Version: lock.Version, Skills: []lockfile.LockedSkill{skill}}
			}

			updates, err := CheckUpdates(cmd.Context(), lock)
			if err != nil {
				return err
			}
			if len(updates) == 0 {
				fmt.Println("全部为最新")
				return nil
			}
			w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
			fmt.Fprintln(w, "SKILL\t本地 commit\t远端 commit")
			for _, update := range updates {
				fmt.Fprintf(w, "%s\t%s\t%s\n",
					update.Name, short(update.LocalCommit), short(update.RemoteCommit))
			}
			if err := w.Flush(); err != nil {
				return err
			}

			// 默认只报不装；--check / --dry-run 强制只报；--all 或指定了名字才动手
			if checkOnly || dryRun || (!all && len(args) == 0) {
				fmt.Println("加 --all 应用全部更新，或指定 skill 名称只更新它。")
				return nil
			}
			if err := applyUpdates(cmd.Context(), projectRoot, &lock, updates); err != nil {
				return err
			}
			return lock.Save(projectRoot)
		},
	}
	cmd.Flags().BoolVar(&checkOnly, "check", false, "只检查不应用")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "只报出会更新什么，不落盘")
	cmd.Flags().BoolVar(&all, "all", false, "应用全部更新")
	return cmd
}

// applyUpdates 按仓库分组应用更新：同一个仓库只 clone 一次，
// 再从同一份检出里取各个 Skill 的子目录。装几十个 Skill 的仓库时
// 这是数量级的差别。
func applyUpdates(ctx context.Context, projectRoot string, lock *lockfile.Lock, updates []UpdateInfo) error {
	groups := map[string][]UpdateInfo{}
	var order []string
	for _, update := range updates {
		if _, seen := groups[update.Repository]; !seen {
			order = append(order, update.Repository)
		}
		groups[update.Repository] = append(groups[update.Repository], update)
	}

	for _, repository := range order {
		group := groups[repository]
		work, err := os.MkdirTemp("", "skillmesh-update-*")
		if err != nil {
			return err
		}
		root := filepath.Join(work, "repo")
		commit, err := gitx.Clone(ctx, repository, group[0].RemoteCommit, root)
		if err != nil {
			os.RemoveAll(work)
			return err
		}
		for _, update := range group {
			if err := applyOne(root, projectRoot, lock, update, commit); err != nil {
				fmt.Fprintf(os.Stderr, "更新 %s 失败: %v\n", update.Name, err)
			}
		}
		os.RemoveAll(work)
	}
	return nil
}

// applyOne 走「校验 → 原子替换」，失败保留当前可用版本。
func applyOne(root, projectRoot string, lock *lockfile.Lock, update UpdateInfo, commit string) error {
	skillDir := filepath.Join(root, filepath.FromSlash(update.Path))
	if err := skillscan.ValidateDir(skillDir, skillscan.DefaultLimits); err != nil {
		return err
	}
	name := skillscan.SkillName(skillDir)

	for _, agentName := range update.Agents {
		base, err := agent.Dir(cfg, agentName, scopeOf(update.Scope, projectRoot), projectRoot)
		if err != nil {
			return err
		}
		target := filepath.Join(base, name)
		if err := installer.InstallAtomic(skillDir, target, true); err != nil {
			return err
		}
		if err := installer.MarkInstalled(target, update.Repository); err != nil {
			return err
		}
	}
	lock.Upsert(lockfile.LockedSkill{
		Name:           update.Name,
		Repository:     update.Repository,
		Path:           update.Path,
		RefType:        refTypeOf(update.Ref),
		Ref:            update.Ref,
		ResolvedCommit: commit,
		Scope:          scopeOf(update.Scope, projectRoot),
		Agents:         update.Agents,
	})
	fmt.Printf("已更新 %s 到 %s\n", name, short(commit))
	return nil
}

func newLockCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "lock",
		Short: "锁文件维护",
	}
	cmd.AddCommand(&cobra.Command{
		Use:   "refresh",
		Short: "重新解析来源并更新锁文件",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			projectRoot := mustGetwd()
			lock, err := lockfile.Load(projectRoot)
			if err != nil {
				return err
			}
			if len(lock.Skills) == 0 {
				fmt.Println("锁文件为空，无需刷新")
				return nil
			}
			updates, err := CheckUpdates(cmd.Context(), lock)
			if err != nil {
				return err
			}
			if len(updates) == 0 {
				fmt.Println("全部为最新，锁文件未变")
				return nil
			}
			if err := applyUpdates(cmd.Context(), projectRoot, &lock, updates); err != nil {
				return err
			}
			if err := lock.Save(projectRoot); err != nil {
				return err
			}
			fmt.Println("锁文件已刷新")
			return nil
		},
	})
	return cmd
}
