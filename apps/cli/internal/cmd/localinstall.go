package cmd

import (
	"fmt"
	"os"
	"path/filepath"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/installer"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
)

// localInstallOptions 是一次「从目录装 Skill」的参数。
//
// Git 仓库检出后与用户给的本地目录走的是同一套流程，差别只在来源元数据。
type localInstallOptions struct {
	// Root 是来源根目录：Git 仓库的检出目录，或用户给的本地目录。
	Root string
	// Source 是来源标识，写进安装标记（.skillmesh-install）。
	// 本地来源用 "local:" + 绝对路径：两个不同的本地目录装同一个名字
	// 要被认成不同来源，否则会互相覆盖。
	Source string
	// SourceType 是 git 或 local，写进锁文件。
	SourceType string
	// Repository 是 Git 地址；本地来源留空。
	Repository string
	Provider   string
	RefType    string
	Ref        string
	Commit     string
	Subpath    string
	All        bool
	Scope      string
	Agents     []string
	UpdatePolicy string
	Replace    bool
	DryRun     bool
}

// installFromDir 把 root 下的 Skill 装到目标 Agent 目录，并把来源写进锁文件。
//
// 本地来源（SourceType=local）只装到本机，不进注册中心：它没有签名、
// 没走过服务端的扫描与审核，登记上去等于让未验证的内容冒充发布方的版本
// （详细设计 §7.1 的来源与信任分级）。来源只落在锁文件里。
func installFromDir(opts localInstallOptions) error {
	candidates, err := LocateCandidates(opts.Root, opts.Subpath, opts.All)
	if err != nil {
		return err
	}

	projectRoot := projectRootDir()
	targetScope := opts.Scope
	if targetScope == "" {
		targetScope = "project"
		if projectRoot == "" {
			targetScope = "user"
		}
	}
	// --agent all：装到所有已启用的适配器（详细设计 §3.5）。
	targets := opts.Agents
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
	plan := PlanInstalls(opts.Root, candidates, base, opts.Source, opts.Replace)
	reportPlan(plan, base, opts.DryRun)
	if opts.DryRun {
		return nil
	}

	refType := opts.RefType
	if opts.SourceType == "local" {
		// 本地目录没有分支、标签那回事。
		refType = "local"
	} else {
		refType = RefTypeOrDetect(opts.Root, opts.Ref, refType)
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
			agentBase, err := agent.Dir(Cfg, agentName, targetScope, projectRoot)
			if err != nil {
				plan.Fail = append(plan.Fail, failItem{Path: item.RelPath, Why: err.Error()})
				continue
			}
			target := filepath.Join(agentBase, item.Name)
			if err := installer.InstallAtomic(item.Candidate.Dir, target, true); err != nil {
				plan.Fail = append(plan.Fail, failItem{Path: item.RelPath, Why: err.Error()})
				continue
			}
			if err := installer.MarkInstalled(target, opts.Source); err != nil {
				plan.Fail = append(plan.Fail, failItem{Path: item.RelPath, Why: err.Error()})
				continue
			}
			// 多 Agent 时合并到同一条：锁文件一条对应一个 Skill，
			// Agents 列出装到了哪些（Upsert 会按名字替换掉前一条）。
			lock.Upsert(mergeAgents(&lock, lockfile.LockedSkill{
				Name:           item.Name,
				Repository:     opts.Repository,
				Provider:       opts.Provider,
				SourceType:     opts.SourceType,
				Path:           item.RelPath,
				RefType:        refType,
				Ref:            opts.Ref,
				ResolvedCommit: opts.Commit,
				Scope:          targetScope,
				UpdatePolicy:   opts.UpdatePolicy,
				InstalledHash:  InstalledDigest(target),
				Agents:         []string{agentName},
				// 记下实际装到哪：Agent 目录改了以后，
				// 按配置重算的位置与当初装的对不上（设计文档 §9.1）。
				TargetPath: target,
				// 许可证识别结果只是线索，不等于可以再分发。
				License: skillscan.DetectLicense(item.Candidate.Dir, opts.Root),
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
		fmt.Printf("  %s —— %s\n", item.Name, skillscan.LicenseLine(item.Candidate.Dir, opts.Root))
	}
	if len(plan.Fail) > 0 {
		fmt.Printf("失败 %d 个：\n", len(plan.Fail))
		for _, failure := range plan.Fail {
			fmt.Printf("  %s —— %s\n", failure.Path, failure.Why)
		}
	}
	fmt.Printf("锁文件 %s\n", lockfile.FileName)
	return nil
}

// extractToTemp 把 zip 解到临时目录，返回解出来的 Skill 根目录。
//
// 解包的路径安全由 installer.ExtractZip 兜着（防 zip-slip 与归档炸弹）。
func extractToTemp(archive string) (string, func(), error) {
	data, err := os.ReadFile(archive)
	if err != nil {
		return "", nil, err
	}
	work, err := os.MkdirTemp("", "skillmesh-local-*")
	if err != nil {
		return "", nil, err
	}
	cleanup := func() { os.RemoveAll(work) }
	root, err := installer.ExtractZip(data, work)
	if err != nil {
		cleanup()
		return "", nil, err
	}
	return root, cleanup, nil
}
