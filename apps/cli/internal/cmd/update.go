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
	Name          string
	Repository    string
	Path          string
	Ref           string
	Scope         string
	InstalledHash string
	SkillID       string
	Agents        []string
	LocalCommit   string
	RemoteCommit  string
}

// lsRemote 包一层是为了测试能替换它，统计同一个仓库被查了几次。
var lsRemote = gitx.LsRemote

// autoSafe 数一数有多少个来源声明了「无本地改动时自动更新」。
func autoSafe(lock lockfile.Lock) int {
	count := 0
	for _, entry := range lock.Skills {
		if entry.UpdatePolicy == "auto_safe" && entry.Repository != "" {
			count++
		}
	}
	return count
}

// filterAutoSafe 只保留 auto_safe 的来源，其余交给用户显式处理。
func filterAutoSafe(lock lockfile.Lock, updates []UpdateInfo) []UpdateInfo {
	policies := map[string]string{}
	for _, entry := range lock.Skills {
		policies[entry.Name] = entry.UpdatePolicy
	}
	var kept []UpdateInfo
	for _, update := range updates {
		if policies[update.Name] == "auto_safe" {
			kept = append(kept, update)
		}
	}
	return kept
}

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
				Name:          skill.Name,
				Repository:    skill.Repository,
				Path:          skill.Path,
				Ref:           skill.Ref,
				Scope:         skill.Scope,
				InstalledHash: skill.InstalledHash,
				SkillID:       skill.SourceID,
				Agents:        skill.Agents,
				LocalCommit:   skill.ResolvedCommit,
				RemoteCommit:  remote,
			})
		}
	}
	return updates, nil
}

func newUpdateCmd() *cobra.Command {
	var checkOnly, all, dryRun, auto bool
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

			// 不给名字也不给 --all：只报；除非这些来源的策略是 auto_safe，
			// 那就该在确认没本地改动后自动更新（设计文档 §7.2）。
			targeted := auto || all || len(args) > 0
			if !targeted {
				fmt.Println("加 --all 应用全部更新，或指定 skill 名称只更新它。")
				return nil
			}
			// 只给了 --auto 时，只动 auto_safe 的来源；手动指定的名字/--all 照旧全量
			selected := updates
			if auto {
				selected = filterAutoSafe(lock, updates)
				if len(selected) == 0 {
					fmt.Println("没有标记 auto_safe 的来源，跳过。")
					return nil
				}
			}
			if err := applyUpdates(cmd.Context(), projectRoot, &lock, selected,
				dryRun || checkOnly); err != nil {
				return err
			}
			if dryRun || checkOnly {
				return nil
			}
			return lock.Save(projectRoot)
		},
	}
	cmd.Flags().BoolVar(&checkOnly, "check", false, "只检查不应用")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "只报出会更新什么，不落盘")
	cmd.Flags().BoolVar(&auto, "auto", false, "只应用标记了 auto_safe 且本地无改动的来源")
	cmd.Flags().BoolVar(&all, "all", false, "应用全部更新")
	return cmd
}

// applyUpdates 按仓库分组应用更新：同一个仓库只 clone 一次，
// 再从同一份检出里取各个 Skill 的子目录。装几十个 Skill 的仓库时
// 这是数量级的差别。
func applyUpdates(ctx context.Context, projectRoot string, lock *lockfile.Lock,
	updates []UpdateInfo, dryRun bool) error {
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
			modified, err := locallyModified(projectRoot, update)
			if err != nil {
				fmt.Fprintf(os.Stderr, "检查 %s 的本地改动失败: %v\n", update.Name, err)
				continue
			}
			if modified {
				// 本地有未提交修改：保留本地版本，不覆盖（设计文档 §3.3、§6.3）。
				fmt.Printf("跳过 %s —— 本地有改动，保留本地版本\n", update.Name)
				continue
			}
			if dryRun {
				previewUpdate(root, projectRoot, update)
				continue
			}
			if err := applyOne(ctx, root, projectRoot, lock, update, commit); err != nil {
				fmt.Fprintf(os.Stderr, "更新 %s 失败: %v\n", update.Name, err)
			}
		}
		os.RemoveAll(work)
	}
	return nil
}

// installedDir 定位某个 Skill 现在装在本机的哪个目录。
func installedDir(projectRoot string, update UpdateInfo) (string, error) {
	if len(update.Agents) == 0 {
		return "", fmt.Errorf("%s 没有登记 Agent 目录", update.Name)
	}
	base, err := agent.Dir(cfg, update.Agents[0], scopeOf(update.Scope, projectRoot), projectRoot)
	if err != nil {
		return "", err
	}
	return filepath.Join(base, update.Name), nil
}

// locallyModified 判断装在本机的内容是不是被人改过。
func locallyModified(projectRoot string, update UpdateInfo) (bool, error) {
	dir, err := installedDir(projectRoot, update)
	if err != nil {
		return false, err
	}
	if _, err := os.Stat(dir); err != nil {
		return false, nil // 没装过，谈不上本地改动
	}
	return contentChanged(dir, update.InstalledHash), nil
}

// contentChanged 比对现场内容和安装时记下的摘要。
// 锁文件里没有 installed_hash 的老记录一律放行——不能因为缺字段就拦住用户。
func contentChanged(dir, installedHash string) bool {
	if installedHash == "" {
		return false
	}
	current, err := skillscan.DirDigest(dir)
	if err != nil {
		return false
	}
	return current != installedHash
}

// previewUpdate 只报差异不落盘（--dry-run / --check）。
func previewUpdate(root, projectRoot string, update UpdateInfo) {
	remote := filepath.Join(root, filepath.FromSlash(update.Path))
	local, err := installedDir(projectRoot, update)
	if err != nil {
		fmt.Printf("%s —— 本地还没装，无法预览差异\n", update.Name)
		return
	}
	changes, err := diffTrees(local, remote)
	if err != nil || len(changes) == 0 {
		// 拿不到差异就退化成只报 commit，不该让整批预览失败。
		fmt.Printf("%s —— 只知道 commit 从 %s 变到 %s，看不到文件差异\n",
			update.Name, short(update.LocalCommit), short(update.RemoteCommit))
		return
	}
	fmt.Printf("%s —— %d 个文件有变化%s\n",
		update.Name, len(changes), describeChanges(changes))
}

// applyOne 走「校验 → 原子替换」，失败保留当前可用版本。
func applyOne(ctx context.Context, root, projectRoot string, lock *lockfile.Lock,
	update UpdateInfo, commit string) error {
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
	// 摘要取自刚装下去的那份内容：隐藏文件两边都不计，规则一致。
	installedHash, err := skillscan.DirDigest(skillDir)
	if err != nil {
		installedHash = "" // 算不出来就留空，下次更新不做本地改动判断
	}
	lock.Upsert(lockfile.LockedSkill{
		Name:           update.Name,
		Repository:     update.Repository,
		Path:           update.Path,
		RefType:        refTypeOf(update.Ref),
		Ref:            update.Ref,
		ResolvedCommit: commit,
		Scope:          scopeOf(update.Scope, projectRoot),
		InstalledHash:  installedHash,
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
			if err := applyUpdates(cmd.Context(), projectRoot, &lock, updates, false); err != nil {
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
