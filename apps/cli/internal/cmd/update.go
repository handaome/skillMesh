package cmd

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/gitx"
	"github.com/handaome/skillmesh/apps/cli/internal/installer"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/oplog"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/handaome/skillmesh/apps/cli/internal/trash"
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
	// UpdatePolicy 与 Provider 是原锁文件里的：更新后要原样带回，
	// 不然「自动更新」策略和来源类型更新一次就没了。
	UpdatePolicy string
	Provider     string
	// TargetPaths 是锁文件里记的安装路径（Agent 名 -> 路径）。
	// 更新要覆盖的就是这个位置：按配置重算可能算到别处去。
	TargetPaths map[string]string
}

// LsRemote 包一层是为了测试能替换它，统计同一个仓库被查了几次。
var LsRemote = gitx.LsRemote

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

// policyOf 取一个来源的更新策略。没登记过按 notify 算——
// notify 是文档里的默认值（设计文档 §7.2）。
func policyOf(lock lockfile.Lock, name string) string {
	for _, entry := range lock.Skills {
		if entry.Name == name {
			if entry.UpdatePolicy == "" {
				return "notify"
			}
			return entry.UpdatePolicy
		}
	}
	return "notify"
}

// FilterByPolicy 按更新策略筛更新项（设计文档 §7.2）。
//
// manual —— 不该出现在后台检查里：策略是「不自动检查或应用」。
// notify —— 后台查、报差异，但不自动应用（用户确认后才应用）。
// auto_safe —— 后台可以自动应用。
func FilterByPolicy(lock lockfile.Lock, updates []UpdateInfo, want string) []UpdateInfo {
	var kept []UpdateInfo
	for _, update := range updates {
		if policyOf(lock, update.Name) == want {
			kept = append(kept, update)
		}
	}
	return kept
}

// FilterAutoSafe 只保留 auto_safe 的来源，其余交给用户显式处理。
func FilterAutoSafe(lock lockfile.Lock, updates []UpdateInfo) []UpdateInfo {
	return FilterByPolicy(lock, updates, "auto_safe")
}

// CheckUpdates 只调 git ls-remote，不下载内容（详细设计 §3.7）。
//
// 一个仓库里可能装了几十个 Skill（--all 装的），但远端 commit 只有一个：
// 所以按「仓库 + ref」缓存，每个仓库只查一次。
//
// skipManual 为真时不查 manual 策略的来源：策略是「不自动检查或应用」，
// 后台探测它就违背了用户的意图（设计文档 §7.2）。
func CheckUpdates(ctx context.Context, lock lockfile.Lock) ([]UpdateInfo, error) {
	return checkUpdates(ctx, lock, false)
}

// CheckUpdatesBackground 是后台巡检用的检查：跳过 manual 策略的来源。
func CheckUpdatesBackground(ctx context.Context, lock lockfile.Lock) ([]UpdateInfo, error) {
	return checkUpdates(ctx, lock, true)
}

func checkUpdates(ctx context.Context, lock lockfile.Lock, skipManual bool) ([]UpdateInfo, error) {
	remoteCache := map[string]string{}
	var updates []UpdateInfo
	for _, skill := range lock.Skills {
		if skill.Repository == "" {
			continue
		}
		// 固定到 commit 的来源不跟随分支：它只在显式指定目标时才动
		// （设计文档 §3.7「固定到 commit/tag 的来源不会静默跟随分支」）。
		// 顺带躲开了一个坑：ls-remote 按 ref 名匹配，commit sha 匹配不到。
		if skill.RefType == "commit" {
			continue
		}
		if skipManual && policyOf(lock, skill.Name) == "manual" {
			continue
		}
		key := skill.Repository + "\x00" + skill.Ref
		remote, cached := remoteCache[key]
		if !cached {
			var err error
			remote, err = LsRemote(ctx, skill.Repository, skill.Ref)
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
				UpdatePolicy:  skill.UpdatePolicy,
				Provider:      skill.Provider,
				TargetPaths:   skill.TargetPaths,
			})
		}
	}
	return updates, nil
}

// updatePolicies 是 --policy 允许的取值（设计文档 §3.7）。
//
// auto_safe 是 auto-safe 的下划线写法：配置里的 sync.default_update_policy
// 用的是下划线，命令行两种都收。
var updatePolicies = []string{"manual", "notify", "auto-safe", "auto_safe"}

func NewUpdateCmd() *cobra.Command {
	var checkOnly, all, auto, saveLocalChanges bool
	var toVersion, toCommit, policy, skillName string
	cmd := &cobra.Command{
		Use:   "update [skill-name]",
		Short: "检查或应用更新",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if toVersion != "" && toCommit != "" {
				return fmt.Errorf("--to 与 --to-commit 互斥：一次只指定一个目标")
			}
			if policy != "" && !contains(updatePolicies, policy) {
				return fmt.Errorf("--policy 只能是 %s，收到 %q",
					strings.Join(updatePolicies, " / "), policy)
			}
			if skillName != "" && len(args) > 0 {
				return fmt.Errorf("--skill 与位置参数只能给一个")
			}
			name := skillName
			if name == "" && len(args) == 1 {
				name = args[0]
			}
			projectRoot := projectRootDir()
			lock, err := lockfile.Load(projectRoot)
			if err != nil {
				return err
			}
			// 指定名字时只查它一个，但保存时仍用完整的锁文件：
			// 把 lock 换成单条会让其余条目在 Save 时消失（设计文档 §9.1）。
			check := lock
			if name != "" {
				skill, ok := lock.Find(name)
				if !ok {
					return fmt.Errorf("锁文件中没有 %s", name)
				}
				check = lockfile.Lock{Version: lock.Version, Skills: []lockfile.LockedSkill{skill}}
			}

			// 明确指定目标：不查远端，直接装到那个目标（设计文档 §3.7）。
			if toVersion != "" || toCommit != "" {
				return updateToTarget(cmd.Context(), projectRoot, &lock, check,
					toVersion, toCommit, saveLocalChanges)
			}

			updates, err := CheckUpdates(cmd.Context(), check)
			if err != nil {
				return err
			}
			if len(updates) == 0 {
				fmt.Println("全部为最新")
				return nil
			}
			w := newTable(os.Stdout)
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
			targeted := auto || all || name != ""
			switch policy {
			case "auto-safe", "auto_safe":
				// 这次就按「无本地改动才升级」的策略来（设计文档 §3.7）。
				targeted = true
			case "manual":
				// manual 不执行自动升级：只报。
				fmt.Println("策略是 manual：不自动升级。")
				return nil
			case "notify":
				// notify 也只提示——表已经打出来了，不动手。
				fmt.Println("策略是 notify：只提示，不自动升级。")
				return nil
			}
			if !targeted {
				fmt.Println("加 --all 应用全部更新，或指定 skill 名称只更新它。")
				return nil
			}
			// 只给了 --auto 时，只动 auto_safe 的来源；手动指定的名字/--all 照旧全量
			selected := updates
			if auto && policy == "" {
				selected = FilterAutoSafe(lock, updates)
				if len(selected) == 0 {
					fmt.Println("没有标记 auto_safe 的来源，跳过。")
					return nil
				}
			}
			if err := applyUpdates(cmd.Context(), projectRoot, &lock, selected,
				globalDryRun || checkOnly, saveLocalChanges); err != nil {
				return err
			}
			if globalDryRun || checkOnly {
				return nil
			}
			return lock.Save(projectRoot)
		},
	}
	cmd.Flags().BoolVar(&checkOnly, "check", false, "只检查不应用")
	cmd.Flags().BoolVar(&auto, "auto", false, "只应用标记了 auto_safe 且本地无改动的来源")
	cmd.Flags().BoolVar(&all, "all", false, "应用全部更新")
	cmd.Flags().StringVar(&skillName, "skill", "", "只更新这一个 Skill")
	cmd.Flags().StringVar(&toVersion, "to", "", "更新到这个版本（Git 来源就是标签或分支）")
	cmd.Flags().StringVar(&toCommit, "to-commit", "", "更新到这个 commit")
	cmd.Flags().StringVar(&policy, "policy", "",
		"这次的更新策略：manual（只报）/ notify（只提示）/ auto-safe（无本地改动才升级）")
	cmd.Flags().BoolVar(&saveLocalChanges, "save-local-changes", false,
		"本地有改动时先把差异存成补丁，再继续更新")
	return supportsDryRun(cmd)
}

// updateToTarget 把选中的来源更新到指定的目标。
//
// --to 给的是版本或 ref（Git 来源就是标签），--to-commit 给的是 commit。
// 两者互斥（设计文档 §3.7）。
//
// 注册中心来源不用这条路：回滚到指定版本是 install <名字> --version <版本>
// （设计文档 §3.7「回滚到旧版本」）。
func updateToTarget(ctx context.Context, projectRoot string, lock *lockfile.Lock,
	selected lockfile.Lock, toVersion, toCommit string, saveLocalChanges bool) error {

	for _, skill := range selected.Skills {
		if skill.Repository == "" {
			return fmt.Errorf(
				"%s 不是 Git 来源：更新到指定版本请用 skillmesh install %s --version %s",
				skill.Name, skill.Name, orDash(toVersion))
		}
		commit := toCommit
		ref := skill.Ref
		if commit == "" {
			resolved, err := LsRemote(ctx, skill.Repository, toVersion)
			if err != nil {
				return fmt.Errorf("解析 %s 的 %s 失败: %w", skill.Repository, toVersion, err)
			}
			commit = resolved
			ref = toVersion
		}
		update := UpdateInfo{
			Name:          skill.Name,
			Repository:    skill.Repository,
			Path:          skill.Path,
			Ref:           ref,
			Scope:         skill.Scope,
			InstalledHash: skill.InstalledHash,
			SkillID:       skill.SourceID,
			Agents:        skill.Agents,
			LocalCommit:   skill.ResolvedCommit,
			RemoteCommit:  commit,
			UpdatePolicy:  skill.UpdatePolicy,
			Provider:      skill.Provider,
			TargetPaths:   skill.TargetPaths,
		}
		work, err := os.MkdirTemp("", "skillmesh-update-*")
		if err != nil {
			return err
		}
		root := filepath.Join(work, "repo")
		cloned, err := gitx.Clone(ctx, skill.Repository, commit, root)
		if err != nil {
			os.RemoveAll(work)
			return err
		}
		modified, err := locallyModified(projectRoot, update)
		if err != nil {
			fmt.Fprintf(os.Stderr, "检查 %s 的本地改动失败: %v\n", update.Name, err)
			os.RemoveAll(work)
			continue
		}
		if modified {
			if !saveLocalChanges {
				fmt.Printf("跳过 %s —— 本地有改动，保留本地版本\n", update.Name)
				recordUpdateConflict(update)
				os.RemoveAll(work)
				continue
			}
			patch, err := saveLocalPatch(projectRoot, root, update)
			if err != nil {
				fmt.Fprintf(os.Stderr, "保存 %s 的本地改动失败: %v\n", update.Name, err)
				os.RemoveAll(work)
				continue
			}
			fmt.Printf("本地改动已存成补丁 %s\n", patch)
		}
		err = ApplyOne(ctx, root, projectRoot, lock, update, cloned)
		os.RemoveAll(work)
		if err != nil {
			fmt.Fprintf(os.Stderr, "更新 %s 失败: %v\n", update.Name, err)
			continue
		}
	}
	return lock.Save(projectRoot)
}

// applyUpdates 按仓库分组应用更新：同一个仓库只 clone 一次，
// 再从同一份检出里取各个 Skill 的子目录。装几十个 Skill 的仓库时
// 这是数量级的差别。
//
// saveLocalChanges 为真时，本地有改动先把差异存成补丁再更新；
// 否则保留本地版本不动（设计文档 §3.7）。
func applyUpdates(ctx context.Context, projectRoot string, lock *lockfile.Lock,
	updates []UpdateInfo, dryRun, saveLocalChanges bool) error {
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
				if !saveLocalChanges {
					// 本地有未提交修改：保留本地版本，不覆盖（设计文档 §3.3、§6.3）。
					fmt.Printf("跳过 %s —— 本地有改动，保留本地版本\n", update.Name)
					recordUpdateConflict(update)
					continue
				}
				// 改动留一份补丁，更新照做：改没了还能照着补丁贴回去。
				patch, err := saveLocalPatch(projectRoot, root, update)
				if err != nil {
					fmt.Fprintf(os.Stderr, "保存 %s 的本地改动失败: %v\n", update.Name, err)
					continue
				}
				fmt.Printf("本地改动已存成补丁 %s\n", patch)
			}
			if dryRun {
				previewUpdate(root, projectRoot, update)
				continue
			}
			if err := ApplyOne(ctx, root, projectRoot, lock, update, commit); err != nil {
				fmt.Fprintf(os.Stderr, "更新 %s 失败: %v\n", update.Name, err)
			}
		}
		os.RemoveAll(work)
	}
	return nil
}

// recordUpdateConflict 记下「这次更新因为本地有改动没做」。
//
// 更新跳过时只往终端打一行字，终端一滚就没了：过两天用户问
// 「这个 Skill 怎么没更新上去」，没有任何地方查得到。
// 写进本机操作日志，skillmesh logs 能看到这条冲突
// （设计文档 §4.5「基础冲突记录」）。
func recordUpdateConflict(update UpdateInfo) {
	opLog().Record(oplog.ActionUpdateConflict, update.Name,
		fmt.Sprintf("本地有改动，保留本地版本未更新（本地 %s → 远端 %s）",
			short(update.LocalCommit), short(update.RemoteCommit)),
		map[string]string{
			"repository":    update.Repository,
			"local_commit":  update.LocalCommit,
			"remote_commit": update.RemoteCommit,
		})
}

// saveLocalPatch 把「本地这份」与「新版本」的差异写成补丁文件，返回文件路径。
//
// 更新会把本机目录整个换掉，本地改动就此消失。补丁留一份，
// 用户照着它能把改动重新贴回去（设计文档 §3.7「--save-local-changes」）。
//
// 补丁放在配置目录的 patches 下：放项目里会混进提交，放 Agent 目录里
// 会被打包上传。
func saveLocalPatch(projectRoot, newRoot string, update UpdateInfo) (string, error) {
	local, err := installedDir(projectRoot, update)
	if err != nil {
		return "", err
	}
	remote := filepath.Join(newRoot, filepath.FromSlash(update.Path))
	changes, err := DiffTrees(local, remote)
	if err != nil {
		return "", err
	}
	var b strings.Builder
	for _, change := range changes {
		oldText, oldErr := readTextFile(filepath.Join(local, filepath.FromSlash(change.Path)))
		newText, newErr := readTextFile(filepath.Join(remote, filepath.FromSlash(change.Path)))
		if oldErr != nil || newErr != nil {
			continue // 二进制文件写不了补丁，跳过
		}
		diff := UnifiedDiff(change.Path, oldText, newText)
		if diff == "" {
			continue
		}
		b.WriteString(diff)
	}
	if b.Len() == 0 {
		return "", fmt.Errorf("没有可保存的差异")
	}
	dir := filepath.Join(Cfg.ConfigDir, "patches")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	path := filepath.Join(dir, fmt.Sprintf("%s-%s.patch",
		update.Name, time.Now().Format("20060102-150405")))
	if err := os.WriteFile(path, []byte(b.String()), 0o644); err != nil {
		return "", err
	}
	return path, nil
}

// readTextFile 读一个文本文件。读不了（二进制、不是文件）就报错——
// 二进制文件写出来的补丁是乱码，不如不写。
func readTextFile(path string) (string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	if skillscan.IsBinaryContent(data) {
		return "", fmt.Errorf("%s 是二进制文件", path)
	}
	return string(data), nil
}

// installedDir 定位某个 Skill 现在装在本机的哪个目录。
//
// 优先用锁文件里记的路径：Agent 目录改了以后，按配置重算的位置与当初装的
// 对不上（设计文档 §9.1）。
func installedDir(projectRoot string, update UpdateInfo) (string, error) {
	if len(update.Agents) == 0 {
		return "", fmt.Errorf("%s 没有登记 Agent 目录", update.Name)
	}
	if path := update.TargetPaths[update.Agents[0]]; path != "" {
		return path, nil
	}
	base, err := agent.Dir(Cfg, update.Agents[0], scopeOf(update.Scope, projectRoot), projectRoot)
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
	return ContentChanged(dir, update.InstalledHash), nil
}

// ContentChanged 比对现场内容和安装时记下的摘要。
// 锁文件里没有 installed_hash 的老记录一律放行——不能因为缺字段就拦住用户。
func ContentChanged(dir, installedHash string) bool {
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
	changes, err := DiffTrees(local, remote)
	if err != nil || len(changes) == 0 {
		// 拿不到差异就退化成只报 commit，不该让整批预览失败。
		fmt.Printf("%s —— 只知道 commit 从 %s 变到 %s，看不到文件差异\n",
			update.Name, short(update.LocalCommit), short(update.RemoteCommit))
		return
	}
	fmt.Printf("%s —— %d 个文件有变化%s\n",
		update.Name, len(changes), describeChanges(changes))
}

// ApplyOne 走「校验 → 原子替换」，失败保留当前可用版本。
func ApplyOne(ctx context.Context, root, projectRoot string, lock *lockfile.Lock,
	update UpdateInfo, commit string) error {
	skillDir := filepath.Join(root, filepath.FromSlash(update.Path))
	if err := skillscan.ValidateDir(skillDir, skillscan.DefaultLimits); err != nil {
		return err
	}
	// 更新也要扫密钥：上游新推的内容里带真凭据时，
	// 自动更新会把它直接装进 Agent 目录（设计文档 §13「阻止自动更新」）。
	// 发布路径扫的是同一套规则，这里不能放过。
	findings, err := skillscan.ScanSecrets(skillDir)
	if err != nil {
		return err
	}
	if len(findings) > 0 {
		return errors.New("上游版本含疑似凭据，已拒绝更新：\n" +
			skillscan.FormatFindings(findings))
	}
	name, err := skillDirName(skillDir)
	if err != nil {
		return err
	}

	// 目标路径按锁文件里记的来：分类分层（Hermes 的 <category>/<name>/）
	// 与改过位置的 Agent 目录都靠它认对地方。
	paths := map[string]string{}
	for _, agentName := range update.Agents {
		target := update.TargetPaths[agentName]
		if target == "" {
			base, err := agent.Dir(Cfg, agentName, scopeOf(update.Scope, projectRoot), projectRoot)
			if err != nil {
				return err
			}
			target = filepath.Join(base, name)
		}
		if err := installOverwrite(skillDir, name, target); err != nil {
			return err
		}
		if err := installer.MarkInstalled(target, update.Repository); err != nil {
			return err
		}
		paths[agentName] = target
	}
	// 摘要取自刚装下去的那份内容：隐藏文件两边都不计，规则一致。
	installedHash, err := skillscan.DirDigest(skillDir)
	if err != nil {
		installedHash = "" // 算不出来就留空，下次更新不做本地改动判断
	}
	// 锁文件里那条原样留着，只改更新的部分：
	// source_id、许可证这些字段换条新的就丢了，等于让用户重新配一遍。
	entry, ok := lock.Find(update.Name)
	if !ok {
		entry = lockfile.LockedSkill{Name: update.Name}
	}
	entry.Repository = update.Repository
	entry.Path = update.Path
	entry.RefType = RefTypeOf(update.Ref)
	entry.Ref = update.Ref
	entry.ResolvedCommit = commit
	entry.Scope = scopeOf(update.Scope, projectRoot)
	entry.InstalledHash = installedHash
	entry.Agents = update.Agents
	// 策略与来源类型是原条目里的，原样带回：更新一次就把
	// 「自动更新」和来源洗掉，等于让用户重新配一遍。
	entry.UpdatePolicy = update.UpdatePolicy
	entry.Provider = update.Provider
	entry.TargetPaths = paths
	lock.Upsert(entry)
	fmt.Printf("已更新 %s 到 %s\n", name, short(commit))
	return nil
}

// installOverwrite 覆盖安装。
//
// 同名目录不受 SkillMesh 管理时直接拒绝：update 没有 --replace 这类开关，
// 用户自己写的同名 Skill 不该被静默覆盖掉。
// 受管的旧版本先备份进回收站：装砸了还能找回来（skillmesh trash restore）。
func installOverwrite(skillDir, name, target string) error {
	state, err := installer.ClassifyTarget(target)
	if err != nil {
		return err
	}
	switch state {
	case installer.TargetAbsent:
	case installer.TargetSymlink:
		return &installer.TargetConflictError{TargetPath: target, Reason: installer.ReasonSymlink}
	case installer.TargetFile:
		return &installer.TargetConflictError{TargetPath: target, Reason: installer.ReasonNotDir}
	default:
		if !installer.IsManaged(target) {
			return &installer.TargetConflictError{TargetPath: target, Reason: installer.ReasonUnmanaged}
		}
		item, err := backupInstalled(name, target)
		if err != nil {
			return err
		}
		fmt.Printf("旧版本已备份到回收站 %s\n", item.Entry)
	}
	return installer.InstallAtomic(skillDir, target, true)
}

// backupInstalled 把旧版本备份进回收站。
//
// 用拷的而不是搬的：搬走会让目标目录空着，装砸了还得再搬回来；
// 拷贝跨盘也行，装的时候由 installer 的原子替换兜底。
func backupInstalled(name, target string) (trash.Item, error) {
	if err := os.MkdirAll(Cfg.ConfigDir, 0o755); err != nil {
		return trash.Item{}, err
	}
	staging, err := os.MkdirTemp(Cfg.ConfigDir, "skillmesh-backup-*")
	if err != nil {
		return trash.Item{}, err
	}
	defer os.RemoveAll(staging)
	copied := filepath.Join(staging, name)
	if err := copyDir(target, copied); err != nil {
		return trash.Item{}, fmt.Errorf("备份旧版本失败: %w", err)
	}
	// 从同盘移进回收站：os.Rename 跨盘搬不动。
	return trash.NewStore(Cfg.ConfigDir).Move(name, copied)
}

// copyDir 整棵复制目录。隐藏文件也要拷：备份里的 .skillmesh-install 标记
// 丢了的话，还原回来的目录就不受管，下次更新会拒绝覆盖它。
func copyDir(src, dst string) error {
	return filepath.Walk(src, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(src, path)
		if err != nil {
			return err
		}
		out := filepath.Join(dst, rel)
		if info.IsDir() {
			return os.MkdirAll(out, 0o755)
		}
		if !info.Mode().IsRegular() {
			return nil // 符号链接之类的不拷
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		return os.WriteFile(out, data, info.Mode().Perm())
	})
}
