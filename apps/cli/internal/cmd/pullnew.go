package cmd

import (
	"context"
	"path/filepath"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/handaome/skillmesh/apps/cli/internal/syncstate"
)

// pullNewLabel 是「云端有、本机没有」这批结果的标题。
const pullNewLabel = "拉取云端新增"

// pullMissing 把云端个人空间里有、本机没有的 Skill 装下来。
//
// 这就是「多地同步」的另一半：另一台机器上 publish 过的东西，
// 本机跑一次 sync 就该拿到——不然用户得先知道 restore 这个命令，
// 而 restore 和 sync 在用户眼里本来就是同一件事。
//
// 只拉个人私有空间（mine=true）：团队里别人的 Skill 不该被这个动作带下来。
//
// dropping 是这次要往云端传删除的 Skill id：它们马上就不在云端了，
// 再拉回来等于删了个寂寞。
func pullMissing(ctx context.Context, client *apiclient.Client, options SyncOptions,
	state *syncstate.State, dropping map[string]bool) []syncResult {

	// 只上传、只重试、要求新发现先别动，这三种情况都不该替用户往本机装东西。
	if options.Direction == "upload" || options.OnlyExisting {
		return nil
	}
	skills, err := mySkills(ctx, client)
	if err != nil {
		return []syncResult{{Name: pullNewLabel, Outcome: Failed,
			Detail: "列不出个人空间：" + err.Error()}}
	}
	if len(skills) == 0 {
		return nil
	}
	// 目录看不清时不往里装：装进去的那份用户根本看不见，
	// 「本机没有」这个状态还变了，下一轮会当成云端新增再装一遍
	// （设计文档 §3.6、§4.3 DEL-001）。
	agents, blind := usableAgents(*state, options)
	if len(agents) == 0 {
		return []syncResult{{Name: pullNewLabel, Outcome: Skipped,
			Detail: "本机目录现在看不清（临时不可用或被整个清空），没往本机装东西"}}
	}
	var results []syncResult
	if len(blind) > 0 {
		results = append(results, syncResult{Name: pullNewLabel, Outcome: Skipped,
			Detail: "目录看不清，跳过：" + strings.Join(blind, "、")})
	}
	local := localSkillSlugs(options)
	targets := restoreOptions{
		Agents:      agents,
		Scope:       "user",
		ProjectRoot: projectRootDir(),
		State:       state,
	}
	for _, skill := range skills {
		if !shouldPull(skill, local, dropping, options) {
			continue
		}
		if options.DryRun {
			results = append(results, syncResult{Name: skill.Name, Outcome: WouldPull,
				Detail: "演练：本机没有，正式跑会从云端拉取"})
			continue
		}
		restored := restoreOne(ctx, client, skill, targets)
		results = append(results, syncResult{
			Name:    restored.Name,
			Outcome: syncOutcomeOfRestore(restored.State),
			Detail:  restored.Why,
		})
	}
	return results
}

// shouldPull 这个云端 Skill 要不要往本机装。
func shouldPull(skill apiclient.Skill, local map[string]bool, dropping map[string]bool,
	options SyncOptions) bool {
	if local[skill.Slug] {
		return false // 本机已经有了
	}
	if dropping[skill.ID] {
		return false // 这次正要往云端传删除它，装回来等于删了个寂寞
	}
	return options.Only == "" || skill.Slug == options.Only
}

// usableAgents 挑出可以往里装的 Agent 目录。
//
// 目录临时不可用（网盘掉了、目录被搬走、整个被删了）时不能往里装东西：
// 装进去的那份用户根本看不见，「本机没有」这个状态还变了，
// 下一轮同步会当成云端新增再装一遍（设计文档 §3.6）。
//
// 第二个返回值是看不清的 Agent：用户得知道这批为什么没装。
func usableAgents(state syncstate.State, options SyncOptions) ([]string, []string) {
	all := syncAgentNames(options.Agents)
	roots, err := scanRoots(options)
	if err != nil {
		return all, nil
	}
	unreadable := blindRoots(state, roots)
	var usable, blind []string
	for _, name := range all {
		dir, err := agent.Dir(Cfg, name, "user", "")
		if err != nil || unreadable[dir] {
			blind = append(blind, name)
			continue
		}
		usable = append(usable, name)
	}
	return usable, blind
}

// syncOutcomeOfRestore 把恢复结果换成同步结果的说法。
func syncOutcomeOfRestore(state string) syncOutcome {
	switch state {
	case restoreDone:
		return fetched
	case restoreSame:
		return Skipped
	case restoreSkip:
		return Skipped
	default:
		return Failed
	}
}

// localSkillSlugs 列出本机授权目录里已有的 Skill（按 slug）。
//
// 用全量扫描而不是本次的 targets：targets 可能被 --skill、--skip-new、
// 退避队列过滤过，拿它判断「本机有没有」会把已有的当成没有，重复装一遍。
func localSkillSlugs(options SyncOptions) map[string]bool {
	scanOpts := options
	scanOpts.Only = ""
	scanOpts.OnlyExisting = false
	all, err := SyncTargets(scanOpts)
	if err != nil {
		return nil
	}
	slugs := make(map[string]bool, len(all))
	for _, target := range all {
		// slug 取自目录名，与 syncOne 的口径一致（skill 名可能和目录名不同）。
		slug, err := skillscan.SlugFromDirName(filepath.Base(target.Path))
		if err == nil {
			slugs[slug] = true
		}
	}
	return slugs
}
