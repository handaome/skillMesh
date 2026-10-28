package cmd

import (
	"context"
	"path/filepath"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/handaome/skillmesh/apps/cli/internal/syncstate"
)

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
		return []syncResult{{Name: "拉取云端新增", Outcome: Failed,
			Detail: "列不出个人空间：" + err.Error()}}
	}
	if len(skills) == 0 {
		return nil
	}
	local := localSkillSlugs(options)
	targets := restoreOptions{
		Agents:      syncAgentNames(options.Agents),
		Scope:       "user",
		ProjectRoot: projectRootDir(),
		State:       state,
	}
	var results []syncResult
	for _, skill := range skills {
		if local[skill.Slug] {
			continue
		}
		if dropping[skill.ID] {
			continue
		}
		if options.Only != "" && skill.Slug != options.Only {
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
