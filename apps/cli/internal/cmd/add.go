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

func newAddCmd() *cobra.Command {
	var (
		ref, subpath, agentName, scope, credentialRef string
		doInstall, replace, all, dryRun               bool
	)
	cmd := &cobra.Command{
		Use:   "add <repo-url>",
		Short: "登记一个 Git 来源（加 --install 才安装到 Agent 目录）",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
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
			commit, err := gitx.Clone(cmd.Context(), repo, ref, root)
			if err != nil {
				return err
			}

			candidates, err := locateCandidates(root, subpath, all)
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
			base, err := agent.Dir(cfg, agentName, targetScope, projectRoot)
			if err != nil {
				return err
			}

			// 先把全部候选校验一遍、定好计划，再动手装：
			// 一个 Skill 有问题不该挡住其他全部。
			plan := planInstalls(root, candidates, base, replace)
			reportPlan(plan, base, dryRun)

			if !doInstall {
				fmt.Println("已登记。加 --install 安装到 Agent 目录。")
				return nil
			}
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
				if err := installer.InstallAtomic(item.Candidate.Dir, item.Target, true); err != nil {
					plan.Fail = append(plan.Fail, failItem{Path: item.RelPath, Why: err.Error()})
					continue
				}
				if err := installer.MarkInstalled(item.Target, repo); err != nil {
					plan.Fail = append(plan.Fail, failItem{Path: item.RelPath, Why: err.Error()})
					continue
				}
				lock.Upsert(lockfile.LockedSkill{
					Name:           item.Name,
					Repository:     repo,
					Path:           item.RelPath,
					RefType:        refTypeOf(ref),
					Ref:            ref,
					ResolvedCommit: commit,
					Scope:          targetScope,
					Agents:         []string{agentName},
				})
				installedItems = append(installedItems, item)
				installed++
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
	cmd.Flags().StringVar(&subpath, "path", "", "仓库内 Skill 子目录")
	cmd.Flags().StringVar(&agentName, "agent", "claude-code", "目标 Agent")
	cmd.Flags().StringVar(&scope, "scope", "", "user 或 project（默认有项目根时为 project）")
	cmd.Flags().StringVar(&credentialRef, "credential-ref", "", "本机凭据管理器中的凭据引用")
	cmd.Flags().BoolVar(&doInstall, "install", false, "解析和扫描通过后立即安装")
	cmd.Flags().BoolVar(&all, "all", false, "安装仓库里的全部 Skill")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "只列出会装什么，不实际安装")
	cmd.Flags().BoolVar(&replace, "replace", false, "覆盖不受 SkillMesh 管理的同名目录")
	return cmd
}

// locateCandidates 定位要处理的 Skill 目录。
// 指定 --path 就用它；否则扫描全仓库，多个候选时要求明确选择或用 --all。
func locateCandidates(root, subpath string, all bool) ([]skillscan.Candidate, error) {
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

// planInstalls 校验全部候选并决定装哪些。
// 校验失败只影响它自己；安装名冲突或目标被占则跳过，不静默覆盖。
func planInstalls(root string, candidates []skillscan.Candidate, base string, replace bool) installPlan {
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
		name := skillscan.SkillName(candidate.Dir)
		if previous, duplicate := used[name]; duplicate {
			plan.Skip = append(plan.Skip, skipItem{
				Path: rel,
				Why:  fmt.Sprintf("安装名 %s 与 %s 冲突", name, previous),
			})
			continue
		}
		used[name] = rel

		target := filepath.Join(base, name)
		if err := checkTarget(target, replace); err != nil {
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

// checkTarget 同名且不受管理时默认不覆盖，避免毁掉用户手写的 Skill。
func checkTarget(target string, replace bool) error {
	if _, err := os.Stat(target); err != nil {
		return nil
	}
	if replace || installer.IsManaged(target) {
		return nil
	}
	return fmt.Errorf("目标已存在且不受 SkillMesh 管理: %s（用 --replace 覆盖）", target)
}

func refTypeOf(ref string) string {
	switch {
	case ref == "":
		return "default"
	case len(ref) == 40:
		return "commit"
	default:
		return "branch"
	}
}
