package cmd

import (
	"context"
	"os"
	"path/filepath"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/installer"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/syncstate"
)

// restorePageSize 是一次拉取的条数。个人空间不会有几千个 Skill，
// 200 条足够一次拉完；真超了会提示还有更多。
const restorePageSize = 200

// restore 的结果状态。
const (
	restoreDone   = "已恢复"
	restoreSame   = "无变化"
	restoreSkip   = "跳过"
	restoreFailed = "失败"
)

// restoreResult 是单个 Skill 的恢复结果。
type restoreResult struct {
	Name  string
	State string
	Why   string
	// Risks 是装之前该让人看见的可疑内容，汇总时统一展开。
	Risks string
}

// restoreOptions 是一次恢复的可调项。
type restoreOptions struct {
	Agents      []string
	Scope       string
	Replace     bool
	ProjectRoot string
	// State 是同步基线：恢复完要记下「本机这份就是云端那份」。
	State *syncstate.State
}

// restoreAction 本地已有同名目录时该怎么处理。
type restoreAction int

const (
	// restoreDoInstall 装上去（或覆盖本地那份）。
	restoreDoInstall restoreAction = iota
	// restoreDoSame 内容一致，不用动。
	restoreDoSame
	// restoreDoSkip 跳过：本地那份不是 SkillMesh 管的。
	restoreDoSkip
)

// decideRestore 本地已有同名目录时的处置。
//
// 内容一致 → 不动：重复跑 restore 不该每次都重装一遍。
// 本地是 SkillMesh 装的同一个 Skill → 覆盖成云端那份（这里以云端为准）。
// 本地是别的东西 → 跳过，不替用户做决定（--replace 才覆盖）。
func decideRestore(exists bool, localDigest, remoteDigest, marker, source string,
	replace bool) restoreAction {

	if !exists {
		return restoreDoInstall
	}
	if localDigest != "" && localDigest == remoteDigest {
		return restoreDoSame
	}
	if marker == source || replace {
		return restoreDoInstall
	}
	return restoreDoSkip
}

// mySkills 拉取我名下的全部 Skill，翻完所有分页。
func mySkills(ctx context.Context, client *apiclient.Client) ([]apiclient.Skill, error) {
	var all []apiclient.Skill
	cursor := ""
	for {
		page, err := client.Search(ctx, "", "", "", true, restorePageSize, cursor)
		if err != nil {
			return nil, err
		}
		all = append(all, page.Items...)
		if !page.HasMore || page.NextCursor == nil {
			return all, nil
		}
		cursor = *page.NextCursor
	}
}

// PickBySlug 按名字挑；不给名字就是全要。

// restoreAll 逐个恢复：一个失败不该挡住其他全部。

// restoreOne 恢复一个 Skill：装到各 Agent 目录，并记下同步基线。
func restoreOne(ctx context.Context, client *apiclient.Client, skill apiclient.Skill,
	options restoreOptions) restoreResult {

	result := restoreResult{Name: skill.Slug}
	versions, err := client.Versions(ctx, skill.ID)
	if err != nil {
		result.State = restoreFailed
		result.Why = err.Error()
		return result
	}
	target, err := PickVersion(versions, "", false, lockfile.LockedSkill{}, false)
	if err != nil {
		// 一个还没发过版的 Skill 不该让整批恢复失败。
		result.State = restoreSkip
		result.Why = err.Error()
		return result
	}
	skillDir, cleanup, report, err := fetchArtifact(ctx, client, target)
	if err != nil {
		result.State = restoreFailed
		result.Why = err.Error()
		return result
	}
	defer cleanup()
	result.Risks = report.Risks

	name, err := skillDirName(skillDir)
	if err != nil {
		result.State = restoreFailed
		result.Why = err.Error()
		return result
	}
	remoteDigest := InstalledDigest(skillDir)
	source := "skillmesh:" + skill.ID

	installed := 0
	var notes []string
	for _, agentName := range options.Agents {
		base, err := agent.Dir(Cfg, agentName, options.Scope, options.ProjectRoot)
		if err != nil {
			result.State = restoreFailed
			result.Why = err.Error()
			return result
		}
		installPath := filepath.Join(base, name)
		exists := false
		if _, err := os.Stat(installPath); err == nil {
			exists = true
		}
		localDigest := localDigestOf(installPath)
		switch decideRestore(exists, localDigest, remoteDigest,
			installer.MarkerOf(installPath), source, options.Replace) {
		case restoreDoSame:
			result.State = mergeRestoreState(result.State, restoreSame)
			notes = append(notes, agentName+"：内容与云端一致")
			// 基线照样记：本地已经是这份了，下一次 sync 该知道它跟云端一致。
			recordRestoreBaseline(options.State, skill.ID, name, agentName,
				installPath, target.Version)
			continue
		case restoreDoSkip:
			result.State = mergeRestoreState(result.State, restoreSkip)
			notes = append(notes, agentName+"：本地有同名目录，不受 SkillMesh 管理，没覆盖")
			continue
		}
		// 本地改过、基线还停在上次同步：这是 sync 该报的冲突，
		// 这里直接覆盖等于替用户丢改动。宁可跳过，让人去 sync 里定夺。
		if entry, ok := options.State.Find(name, agentName); ok &&
			entry.BaseHash != "" && localDigest != "" && entry.BaseHash != localDigest {
			result.State = mergeRestoreState(result.State, restoreSkip)
			notes = append(notes, agentName+"：本地有未同步的改动，用 skillmesh sync 处理")
			continue
		}
		if _, err := installIntoAgent(ctx, client, skillDir, installRequest{
			SkillID: skill.ID,
			Version: target,
			Agents:  []string{agentName},
			Scope:   options.Scope,
			Replace: options.Replace,
		}, agentName, options.ProjectRoot); err != nil {
			result.State = restoreFailed
			result.Why = err.Error()
			return result
		}
		installed++
		result.State = mergeRestoreState(result.State, restoreDone)
		notes = append(notes, agentName+"："+installPath)
		recordRestoreBaseline(options.State, skill.ID, name, agentName,
			installPath, target.Version)
	}
	result.Why = strings.Join(notes, "；")
	if installed == 0 {
		return result
	}
	if err := RecordInstall(InstallRecord{
		ProjectRoot:   options.ProjectRoot,
		Name:          name,
		SkillID:       skill.ID,
		Target:        target,
		Scope:         options.Scope,
		Agents:        options.Agents,
		InstalledHash: remoteDigest,
	}); err != nil {
		result.Why += "（锁文件没写成：" + err.Error() + "）"
	}
	_ = client.ReportAuditEvent(ctx, "install.completed", skill.ID, "success",
		map[string]string{"name": name, "version": target.Version})
	return result
}

// mergeRestoreState 汇总多个 Agent 的结果：失败最要紧，其次是装好了。
func mergeRestoreState(current, next string) string {
	rank := map[string]int{
		restoreFailed: 4, restoreDone: 3, restoreSkip: 2, restoreSame: 1,
	}
	if current == "" {
		return next
	}
	if rank[next] > rank[current] {
		return next
	}
	return current
}

// localDigestOf 取本地目录的内容摘要；目录不存在或读不出来返回空串。
func localDigestOf(path string) string {
	if _, err := os.Stat(path); err != nil {
		return ""
	}
	return InstalledDigest(path)
}

// recordRestoreBaseline 记下恢复后的同步基线。
//
// 不记的话，下一次 sync 会把刚恢复的内容当成「本机从没同步过」：
// 云端一旦更新就判成冲突——明明刚拉下来就该跟着云端走。
func recordRestoreBaseline(state *syncstate.State, skillID, name, agentName, path, version string) {
	target := SyncTarget{Name: name, Agent: agentName, Path: path, SkillID: skillID}
	entry := syncstate.Entry{
		Name: name, Agent: agentName, SkillID: skillID,
		Path: path, LastVersion: version,
	}
	saveBaseline(target, &entry)
	state.Upsert(entry)
}
