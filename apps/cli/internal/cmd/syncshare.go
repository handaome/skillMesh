package cmd

import (
	"fmt"
	"os"
	"path/filepath"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
)

// customAgent 是自定义扫描目录的标识（不是真实 Agent）。
//
// 这类目录多半挂的是团队共享盘、网盘，不擅自把里面的技能换成链接。
const customAgent = "custom"

// unifySkills 把各 Agent 目录里的技能实体统一到共享目录：
// 实体只存一份（~/.agent/skills），各 Agent 目录里放指向它的链接。
//
// 只搬实体目录，已经是指向别处的链接一律不碰。
// 链接多半是别的工具（magpie 这类技能管理器）建的，实体归谁管是那个工具的事：
// 抢过来搬走，它的链接就全成了死链。
//
// 共享目录里原本就有的技能也不塞给 Agent：
// 那是用户自己放在那儿的，不该因为共享目录设成了这个路径
// 就被收编进同步范围、白传到云端。
func unifySkills(targets []SyncTarget, options SyncOptions) ([]syncResult, bool) {
	sharedDir := Cfg.Sync.SharedSkillsDir()
	if options.NoShare || sharedDir == "" {
		return nil, false
	}
	if !options.DryRun {
		if err := os.MkdirAll(sharedDir, 0o755); err != nil {
			return []syncResult{{Name: "统一技能目录", Outcome: Failed,
				Detail: "建 " + sharedDir + " 失败: " + err.Error()}}, false
		}
	}
	var results []syncResult
	changed := false
	for _, target := range targets {
		if target.Agent == customAgent {
			continue
		}
		if _, isLink := skillscan.LinkTarget(target.Path); isLink {
			continue // 已经统一过了，或者是别的工具在管
		}
		result, done := unifyOne(target, sharedDir, options)
		results = append(results, result)
		if done {
			changed = true
		}
	}
	// 共享目录里已经收编过的技能，给还没装的 Agent 补上链接。
	linkResults, linked := linkSharedToAgents(sharedDir, options)
	results = append(results, linkResults...)
	return results, changed || linked
}

// unifyOne 把一个技能实体迁进共享目录，并在原位置放链接。
//
// 第二个返回值说「目录结构变了」——变了要重新扫描，后续同步得用新路径。
func unifyOne(target SyncTarget, sharedDir string, options SyncOptions) (syncResult, bool) {
	dest := filepath.Join(sharedDir, target.Name)
	if options.DryRun {
		return syncResult{Name: target.Name, Path: target.Path, Outcome: WouldShare,
			Detail: "演练：会搬进 " + sharedDir + "，原位置换成链接"}, false
	}
	if sameDir(target.Path, dest) {
		return syncResult{}, false
	}
	if _, err := os.Stat(dest); err == nil {
		same, err := sameSkillContent(target.Path, dest)
		if err != nil {
			return syncResult{Name: target.Name, Path: target.Path, Outcome: Failed,
				Detail: "比较内容失败: " + err.Error()}, false
		}
		if !same {
			return syncResult{Name: target.Name, Path: target.Path, Outcome: Skipped,
				Detail: "共享目录里已有同名但内容不同的技能，没有动（请手动处理）"}, false
		}
		// 内容一样：本地这份删掉，共享目录那份留下。
		if err := os.RemoveAll(target.Path); err != nil {
			return syncResult{Name: target.Name, Path: target.Path, Outcome: Failed,
				Detail: "删重复副本失败: " + err.Error()}, false
		}
	} else if err := movePath(target.Path, dest); err != nil {
		return syncResult{Name: target.Name, Path: target.Path, Outcome: Failed,
			Detail: "搬进共享目录失败: " + err.Error()}, false
	}
	if err := replaceWithLink(target.Path, dest); err != nil {
		return syncResult{Name: target.Name, Path: target.Path, Outcome: Failed,
			Detail: "原位置换成链接失败: " + err.Error()}, false
	}
	return syncResult{Name: target.Name, Path: target.Path, Outcome: Shared,
		Detail: "已搬进 " + sharedDir + "，原位置换成链接"}, true
}

// replaceWithLink 把 path 上的东西换成指向 dest 的链接。
func replaceWithLink(path, dest string) error {
	if _, ok := skillscan.LinkTarget(path); ok {
		if err := os.Remove(path); err != nil {
			return fmt.Errorf("删旧链接失败: %w", err)
		}
	} else if err := os.RemoveAll(path); err != nil {
		return fmt.Errorf("删原目录失败: %w", err)
	}
	return skillscan.LinkDir(dest, path)
}

// linkSharedToAgents 给还没装的 Agent 目录补上共享目录里的技能链接。
//
// 只补「已经收编过」的技能——在某个 Agent 目录里已经出现过的。
// 共享目录里本来就有的技能不算收编：把它们塞进 Agent 目录
// 等于把它们拉进同步范围，白传到云端。
//
// 已经有东西的位置不动：用户可能手动放了一份别的实现，覆盖掉就找不回来了。
func linkSharedToAgents(sharedDir string, options SyncOptions) ([]syncResult, bool) {
	names, err := skillNames(sharedDir)
	if err != nil {
		return []syncResult{{Name: "统一技能目录", Outcome: Failed, Detail: err.Error()}}, false
	}
	dirs := agentDirs(options)
	var results []syncResult
	linked := false
	for _, name := range names {
		if !appearsIn(dirs, name) {
			continue // 没被任何 Agent 收编过，不塞给它们
		}
		for _, dir := range dirs {
			link := filepath.Join(dir, name)
			if _, err := os.Lstat(link); err == nil {
				continue // 已经有东西了
			}
			if options.DryRun {
				results = append(results, syncResult{Name: name, Path: link, Outcome: WouldShare,
					Detail: "演练：会给 " + filepath.Base(dir) + " 目录补一个链接"})
				continue
			}
			if err := skillscan.LinkDir(filepath.Join(sharedDir, name), link); err != nil {
				results = append(results, syncResult{Name: name, Path: link, Outcome: Failed,
					Detail: "建链接失败: " + err.Error()})
				continue
			}
			results = append(results, syncResult{Name: name, Path: link, Outcome: Shared,
				Detail: "已给 " + filepath.Base(dir) + " 目录补上链接"})
			linked = true
		}
	}
	return results, linked
}

// agentDirs 是这次要往里放链接的 Agent 目录（共享目录本身排除）。
func agentDirs(options SyncOptions) []string {
	var dirs []string
	for _, agentName := range syncAgentNames(options.Agents) {
		dir, err := agent.Dir(Cfg, agentName, "user", "")
		if err != nil || sameDir(dir, Cfg.Sync.SharedSkillsDir()) {
			continue
		}
		dirs = append(dirs, dir)
	}
	return dirs
}

// appearsIn 判断这个名字是不是已经在某个 Agent 目录里出现过。
func appearsIn(dirs []string, name string) bool {
	for _, dir := range dirs {
		if _, err := os.Lstat(filepath.Join(dir, name)); err == nil {
			return true
		}
	}
	return false
}
