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
	Agents       []string
	LocalCommit  string
	RemoteCommit string
}

// CheckUpdates 只调 git ls-remote，不下载内容（详细设计 §3.7）。
func CheckUpdates(ctx context.Context, lock lockfile.Lock) ([]UpdateInfo, error) {
	var updates []UpdateInfo
	for _, skill := range lock.Skills {
		if skill.Repository == "" {
			continue
		}
		remote, err := gitx.LsRemote(ctx, skill.Repository, skill.Ref)
		if err != nil {
			return nil, fmt.Errorf("检查 %s 失败: %w", skill.Name, err)
		}
		if remote != skill.ResolvedCommit {
			updates = append(updates, UpdateInfo{
				Name:         skill.Name,
				Repository:   skill.Repository,
				Path:         skill.Path,
				Ref:          skill.Ref,
				Agents:       skill.Agents,
				LocalCommit:  skill.ResolvedCommit,
				RemoteCommit: remote,
			})
		}
	}
	return updates, nil
}

func newUpdateCmd() *cobra.Command {
	var checkOnly, all bool
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

			// 默认只报不装；--check 强制只报；--all 或指定了名字才动手
			if checkOnly || (!all && len(args) == 0) {
				fmt.Println("加 --all 应用全部更新，或指定 skill 名称只更新它。")
				return nil
			}
			for _, update := range updates {
				if err := applyUpdate(cmd.Context(), projectRoot, &lock, update); err != nil {
					return err
				}
			}
			return lock.Save(projectRoot)
		},
	}
	cmd.Flags().BoolVar(&checkOnly, "check", false, "只检查不应用")
	cmd.Flags().BoolVar(&all, "all", false, "应用全部更新")
	return cmd
}

// applyUpdate 走「临时目录 → 校验 → 原子替换」，失败保留当前可用版本。
func applyUpdate(ctx context.Context, projectRoot string, lock *lockfile.Lock, update UpdateInfo) error {
	work, err := os.MkdirTemp("", "skillmesh-update-*")
	if err != nil {
		return err
	}
	defer os.RemoveAll(work)

	root := filepath.Join(work, "repo")
	commit, err := gitx.Clone(ctx, update.Repository, update.RemoteCommit, root)
	if err != nil {
		return err
	}
	skillDir := filepath.Join(root, filepath.FromSlash(update.Path))
	if err := skillscan.ValidateDir(skillDir, skillscan.DefaultLimits); err != nil {
		return err
	}
	name := skillscan.SkillName(skillDir)

	for _, agentName := range update.Agents {
		base, err := agent.Dir(cfg, agentName, "user", projectRoot)
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
		RefType:        lockfileRefType(update.Ref),
		Ref:            update.Ref,
		ResolvedCommit: commit,
		Agents:         update.Agents,
	})
	fmt.Printf("已更新 %s 到 %s\n", name, short(commit))
	return nil
}

func lockfileRefType(ref string) string {
	switch {
	case ref == "":
		return "default"
	case len(ref) == 40:
		return "commit"
	default:
		return "branch"
	}
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
			for _, update := range updates {
				if err := applyUpdate(cmd.Context(), projectRoot, &lock, update); err != nil {
					return err
				}
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
