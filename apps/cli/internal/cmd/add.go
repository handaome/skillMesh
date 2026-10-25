package cmd

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/gitx"
	"github.com/handaome/skillmesh/apps/cli/internal/installer"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/spf13/cobra"
)

// CloneRepo 包一层是为了测试能替换掉真的 git 克隆——
// 测试不联网，也就不该依赖远程仓库（跟 update.go 的 LsRemote 同一个理由）。
var CloneRepo = gitx.Clone

func NewAddCmd() *cobra.Command {
	var (
		ref, subpath, scope, credentialRef string
		agentNames                         []string
		refType, updatePolicy              string
		replace, all, dryRun               bool
	)
	cmd := &cobra.Command{
		Use:   "add <repo-url>",
		Short: "从 Git 仓库安装 Skill 到 Agent 目录",
		Long: "解析仓库里的 Skill、扫描校验、安装到目标 Agent 目录，并把来源写进锁文件。\n" +
			"只想看会装什么，加 --dry-run。",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if !contains([]string{"manual", "notify", "auto_safe"}, updatePolicy) {
				return fmt.Errorf("--update-policy 只能是 manual / notify / auto_safe")
			}
			if refType != "" && !contains([]string{"branch", "tag", "commit", "default"}, refType) {
				return fmt.Errorf("--ref-type 只能是 branch / tag / commit / default")
			}
			repo, err := gitx.NormalizeURL(args[0])
			if err != nil {
				return err
			}
			if all && subpath != "" {
				return fmt.Errorf("--all 与 --path 不能同时用")
			}
			if credentialRef != "" {
				fmt.Printf("使用凭据引用 %s（不接受明文令牌）\n", credentialRef)
			}

			work, err := os.MkdirTemp("", "skillmesh-add-*")
			if err != nil {
				return err
			}
			defer os.RemoveAll(work)

			root := filepath.Join(work, "repo")
			commit, err := CloneRepo(cmd.Context(), repo, ref, root)
			if err != nil {
				return err
			}

			candidates, err := LocateCandidates(root, subpath, all)
			if err != nil {
				return err
			}

			projectRoot := mustGetwd()
			targetScope := scope
			if targetScope == "" {
				targetScope = "project"
				if projectRoot == "" {
					targetScope = "user"
				}
			}
			// --agent all：装到所有已启用的适配器（详细设计 §3.5）。
			targets := agentNames
			if len(targets) == 1 && targets[0] == "all" {
				targets = syncAgentNames(nil)
			}
			if len(targets) == 0 {
				return fmt.Errorf("没有已启用的 Agent 可装")
			}

			base, err := agent.Dir(Cfg, targets[0], targetScope, projectRoot)
			if err != nil {
				return err
			}

			// 先把全部候选校验一遍、定好计划，再动手装：
			// 一个 Skill 有问题不该挡住其他全部。
			plan := PlanInstalls(root, candidates, base, replace)
			reportPlan(plan, base, dryRun)

			if dryRun {
				return nil
			}

			lock, err := lockfile.Load(projectRoot)
			if err != nil {
				return err
			}
			installed := 0
			var installedItems []planItem
			for _, item := range plan.Install {
				// 每个目标 Agent 都装一份；一个失败不影响别的。
				installedHere := false
				for _, agentName := range targets {
					base, err := agent.Dir(Cfg, agentName, targetScope, projectRoot)
					if err != nil {
						plan.Fail = append(plan.Fail, failItem{Path: item.RelPath, Why: err.Error()})
						continue
					}
					target := filepath.Join(base, item.Name)
					if err := installer.InstallAtomic(item.Candidate.Dir, target, true); err != nil {
						plan.Fail = append(plan.Fail, failItem{Path: item.RelPath, Why: err.Error()})
						continue
					}
					if err := installer.MarkInstalled(target, repo); err != nil {
						plan.Fail = append(plan.Fail, failItem{Path: item.RelPath, Why: err.Error()})
						continue
					}
					// 多 Agent 时合并到同一条：锁文件一条对应一个 Skill，
					// Agents 列出装到了哪些（Upsert 会按名字替换掉前一条）。
					lock.Upsert(mergeAgents(&lock, lockfile.LockedSkill{
						Name:           item.Name,
						Repository:     repo,
						Provider:       providerOf(repo),
						Path:           item.RelPath,
						RefType:        RefTypeOrDetect(root, ref, refType),
						Ref:            ref,
						ResolvedCommit: commit,
						Scope:          targetScope,
						UpdatePolicy:   updatePolicy,
						InstalledHash:  InstalledDigest(target),
						Agents:         []string{agentName},
						// 记下实际装到哪：Agent 目录改了以后，
						// 按配置重算的位置与当初装的对不上（设计文档 §9.1）。
						TargetPath: target,
						// 许可证识别结果只是线索，不等于可以再分发。
						License: skillscan.DetectLicense(item.Candidate.Dir, root),
					}))
					installedHere = true
				}
				if installedHere {
					installedItems = append(installedItems, item)
					installed++
				}
			}
			if err := lock.Save(projectRoot); err != nil {
				return err
			}
			fmt.Printf("\n已安装 %d 个 Skill 到 %s\n", installed, base)
			for _, item := range installedItems {
				fmt.Printf("  %s —— %s\n", item.Name, skillscan.LicenseLine(item.Candidate.Dir, root))
			}
			if len(plan.Fail) > 0 {
				fmt.Printf("失败 %d 个：\n", len(plan.Fail))
				for _, failure := range plan.Fail {
					fmt.Printf("  %s —— %s\n", failure.Path, failure.Why)
				}
			}
			fmt.Printf("锁文件 %s\n", lockfile.FileName)
			return nil
		},
	}
	cmd.Flags().StringVar(&ref, "ref", "", "分支、标签或 commit")
	cmd.Flags().StringVar(&refType, "ref-type", "",
		"ref 的类型：branch / tag / commit / default（不填则自己判断）")
	cmd.Flags().StringVar(&subpath, "path", "", "仓库内 Skill 子目录")
	cmd.Flags().StringSliceVar(&agentNames, "agent", []string{"claude-code"},
		"目标 Agent，可重复指定；all 表示所有已启用的适配器")
	cmd.Flags().StringVar(&scope, "scope", "", "user 或 project（默认有项目根时为 project）")
	cmd.Flags().StringVar(&credentialRef, "credential-ref", "", "本机凭据管理器中的凭据引用")
	cmd.Flags().BoolVar(&all, "all", false, "安装仓库里的全部 Skill")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "只列出会装什么，不实际安装")
	cmd.Flags().BoolVar(&replace, "replace", false, "覆盖不受 SkillMesh 管理的同名目录")
	cmd.Flags().StringVar(&updatePolicy, "update-policy", "notify",
		"更新策略：manual（不动）/ notify（默认，只提示）/ auto_safe（无本地改动时自动更新）")
	// --install 现在是默认行为，留着只为兼容旧脚本（写 --install 不报错）。
	// 藏起来：帮助里只该出现 --dry-run 这一个「装不装」的开关。
	cmd.Flags().Bool("install", true, "解析和扫描通过后立即安装（默认行为，已废弃）")
	_ = cmd.Flags().MarkDeprecated("install", "add 默认就安装，用 --dry-run 只看不装")
	return cmd
}

// mergeAgents 把新条目并进锁文件里已有的那一条。
//
// 同一个 Skill 装到多个 Agent 时，锁文件里是一条（Agents 列出装到哪几个），
// 不是多条——Upsert 按名字替换，直接写会把前一个 Agent 的记录洗掉。
func mergeAgents(lock *lockfile.Lock, entry lockfile.LockedSkill) lockfile.LockedSkill {
	if existing, ok := lock.Find(entry.Name); ok {
		for _, name := range existing.Agents {
			if !contains(entry.Agents, name) {
				entry.Agents = append(entry.Agents, name)
			}
		}
	}
	return entry
}

// LocateCandidates 定位要处理的 Skill 目录。
// 指定 --path 就用它；否则扫描全仓库，多个候选时要求明确选择或用 --all。
func LocateCandidates(root, subpath string, all bool) ([]skillscan.Candidate, error) {
	if subpath != "" {
		dir := filepath.Join(root, filepath.FromSlash(subpath))
		if _, err := os.Stat(dir); err != nil {
			return nil, fmt.Errorf("子目录不存在: %s", subpath)
		}
		return []skillscan.Candidate{{Dir: dir, Name: filepath.Base(dir)}}, nil
	}
	candidates, err := skillscan.Candidates(root)
	if err != nil {
		return nil, err
	}
	if len(candidates) == 0 {
		return nil, fmt.Errorf("仓库中未找到含 SKILL.md 的目录")
	}
	if !all && len(candidates) > 1 {
		return nil, fmt.Errorf(
			"仓库含 %d 个候选 Skill，请用 --path 指定其中一个，或用 --all 全部安装：%s",
			len(candidates), strings.Join(candidatePaths(root, candidates), ", "))
	}
	return candidates, nil
}

func candidatePaths(root string, candidates []skillscan.Candidate) []string {
	paths := make([]string, 0, len(candidates))
	for _, candidate := range candidates {
		rel, err := filepath.Rel(root, candidate.Dir)
		if err != nil {
			rel = candidate.Dir
		}
		paths = append(paths, filepath.ToSlash(rel))
	}
	return paths
}

// planItem 是计划安装的一个 Skill。
type planItem struct {
	Candidate skillscan.Candidate
	Name      string
	Target    string
	RelPath   string
	Risks     []skillscan.Finding
}

// skipItem 是被跳过的 Skill 及原因。
type skipItem struct {
	Path string
	Why  string
}

// failItem 是校验或安装失败的 Skill 及原因。
type failItem struct {
	Path string
	Why  string
}

// installPlan 是一次安装的完整计划。
type installPlan struct {
	Install []planItem
	Skip    []skipItem
	Fail    []failItem
}

// PlanInstalls 校验全部候选并决定装哪些。
// 校验失败只影响它自己；安装名冲突或目标被占则跳过，不静默覆盖。
func PlanInstalls(root string, candidates []skillscan.Candidate, base string, replace bool) installPlan {
	var plan installPlan
	used := map[string]string{} // 安装名 -> 来源路径
	for _, candidate := range candidates {
		rel, err := filepath.Rel(root, candidate.Dir)
		if err != nil {
			rel = candidate.Dir
		}
		rel = filepath.ToSlash(rel)

		if err := skillscan.ValidateDir(candidate.Dir, skillscan.DefaultLimits); err != nil {
			plan.Fail = append(plan.Fail, failItem{Path: rel, Why: err.Error()})
			continue
		}
		name, err := skillDirName(candidate.Dir)
		if err != nil {
			plan.Fail = append(plan.Fail, failItem{Path: rel, Why: err.Error()})
			continue
		}
		if previous, duplicate := used[name]; duplicate {
			plan.Skip = append(plan.Skip, skipItem{
				Path: rel,
				Why:  fmt.Sprintf("安装名 %s 与 %s 冲突", name, previous),
			})
			continue
		}
		used[name] = rel

		target := filepath.Join(base, name)
		if err := CheckTarget(target, replace); err != nil {
			plan.Skip = append(plan.Skip, skipItem{Path: rel, Why: err.Error()})
			continue
		}
		// 命中只提示不拦：装到本地是自己的机器，发布才需要阻断（设计文档 §13）。
		findings, err := skillscan.ScanSecrets(candidate.Dir)
		if err != nil {
			findings = nil
		}
		plan.Install = append(plan.Install, planItem{
			Candidate: candidate, Name: name, Target: target, RelPath: rel, Risks: findings,
		})
	}
	return plan
}

// reportPlan 打印计划。成功的只报数量和前几个示例，
// 跳过和失败要逐条列出来——那些是需要人看的。
func reportPlan(plan installPlan, base string, dryRun bool) {
	action := "将安装"
	if dryRun {
		action = "可安装"
	}
	fmt.Printf("%s %d 个 Skill 到 %s\n", action, len(plan.Install), base)
	for i, item := range plan.Install {
		if i >= 10 {
			fmt.Printf("  ……等共 %d 个\n", len(plan.Install))
			break
		}
		fmt.Printf("  %s\n", item.Name)
		for _, risk := range item.Risks {
			fmt.Printf("      风险：%s:%d %s\n", risk.Path, risk.Line, risk.Rule)
		}
	}
	if len(plan.Skip) > 0 {
		fmt.Printf("跳过 %d 个：\n", len(plan.Skip))
		for _, skip := range plan.Skip {
			fmt.Printf("  %s —— %s\n", skip.Path, skip.Why)
		}
	}
	if len(plan.Fail) > 0 {
		fmt.Printf("失败 %d 个：\n", len(plan.Fail))
		for _, failure := range plan.Fail {
			fmt.Printf("  %s —— %s\n", failure.Path, failure.Why)
		}
	}
}

// CheckTarget 同名且不受管理时默认不覆盖，避免毁掉用户手写的 Skill。
func CheckTarget(target string, replace bool) error {
	if _, err := os.Stat(target); err != nil {
		return nil
	}
	if replace || installer.IsManaged(target) {
		return nil
	}
	return fmt.Errorf("目标已存在且不受 SkillMesh 管理: %s（用 --replace 覆盖）", target)
}

// providerOf 从仓库地址猜出来源类型（设计文档 §7.1）。
// 猜不出就归 generic_git——来源类型是元数据，猜错不会影响功能。
func providerOf(url string) string {
	lower := strings.ToLower(url)
	switch {
	case strings.Contains(lower, "github.com"):
		return "github"
	case strings.Contains(lower, "gitlab.com"):
		return "gitlab"
	case strings.Contains(lower, "gitee.com"):
		return "gitee"
	default:
		return "generic_git"
	}
}

// InstalledDigest 记下装完内容的规范化摘要，update 时用它判断本地有没有被改过。
// 算不出来就留空——下次更新不做判断，而不是拦死用户。
func InstalledDigest(target string) string {
	digest, err := skillscan.DirDigest(target)
	if err != nil {
		return ""
	}
	return digest
}

// RefTypeOf 判定 ref 的类型（设计文档 §7.1：branch / tag / commit / default）。
//
// 只看形状时，tag 与 branch 分不开——「v1.2.0」和「main」长得一样。
// 真要分清得问仓库：RefTypeOfInRepo 查了本地 tag 后才给结论。
func RefTypeOf(ref string) string {
	switch {
	case ref == "":
		return "default"
	case len(ref) == 40:
		return "commit"
	default:
		return "branch"
	}
}

// RefTypeOfInRepo 在已克隆的仓库里判定 ref 类型。
// clone --branch <tag> 会把 tag ref 一起带下来，所以本地查得到。
func RefTypeOfInRepo(repoDir, ref string) string {
	if ref == "" || len(ref) == 40 {
		return RefTypeOf(ref)
	}
	if gitx.HasTag(repoDir, ref) {
		return "tag"
	}
	return "branch"
}

// RefTypeOrDetect 用用户显式指定的类型，没指定就自己判断。
//
// 显式指定优先：「v1.2.0」到底是 tag 还是 branch，问远端才知道——
// 用户说他要的是 tag，那就信他。
func RefTypeOrDetect(repoDir, ref, explicit string) string {
	if explicit != "" {
		return explicit
	}
	return RefTypeOfInRepo(repoDir, ref)
}
