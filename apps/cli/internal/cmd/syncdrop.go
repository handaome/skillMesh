package cmd

import (
	"context"
	"os"
	"path/filepath"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/syncstate"
)

// applyDeletePropagation 处理两个方向的删除传播。
//
//   - 云端删了 → 本地那份移进本机回收站（applyCloudDeletes）。
//   - 本地删了 → 云端软删除（propagateLocalDeletes），要显式开了才做。
//
// 返回处理结果、剩下要继续对账的目标、以及这次要往云端删的 Skill id
// （拉取那一步要跳过它们：云端马上就不留着它们了）。
func applyDeletePropagation(ctx context.Context, client *apiclient.Client,
	state *syncstate.State, scanned []SyncTarget, options SyncOptions) (
	[]syncResult, []SyncTarget, map[string]bool) {

	results, targets := applyCloudDeletes(ctx, client, state, scanned, options)
	dropping := map[string]bool{}
	// 本地删掉的往云端传：得赶在拉取之前——云端还留着它的话，
	// 会被当成「云端有、本机没有」又装回来，删了等于没删。
	if options.PropagateDelete && options.Direction != "download" {
		dropResults, ids := propagateLocalDeletes(ctx, client, state, scanned, options)
		results = append(results, dropResults...)
		dropping = ids
	}
	return results, targets, dropping
}

// propagateLocalDeletes 把「本地目录已经没了」的 Skill 从云端删掉。
//
// 这是删除传播的另一半：云端删了本地跟着删（applyCloudDeletes），
// 本地删了云端也跟着删。
//
// 用户明确要求才做（--propagate-delete，或配置里 sync.propagate_delete 开）：
// 目录扫不到可能只是路径变了、网盘没挂上、Agent 目录搬了家——
// 这些情况下把云端清空，代价太大。
//
// 云端那边是软删除：版本与制品都留着，回收期内能 cloud restore 找回来。
//
// 第二个返回值是「这次要删（或演练时要删）的云端 Skill id」：
// 拉取那一步要跳过它们——云端删掉了就不该再被当成「云端有、本机没有」装回来。
func propagateLocalDeletes(ctx context.Context, client *apiclient.Client,
	state *syncstate.State, scanned []SyncTarget, options SyncOptions) ([]syncResult, map[string]bool) {

	roots, err := scanRoots(options)
	if err != nil {
		// 扫描范围都算不出来，就不动云端。
		return nil, nil
	}
	candidates := removalCandidates(*state, scanned, roots, options)
	if len(candidates) == 0 {
		return nil, nil
	}
	var results []syncResult
	dropping := map[string]bool{}
	archived := map[string]bool{}
	for _, entry := range candidates {
		if archived[entry.SkillID] {
			continue // 同一个云端 Skill 装在多个 Agent 目录下，删一次就够
		}
		result, done := archiveOne(ctx, client, state, entry, options)
		archived[entry.SkillID] = true
		if done {
			dropping[entry.SkillID] = true
		}
		results = append(results, result)
	}
	return results, dropping
}

// archiveOne 把一个本地已删掉的 Skill 从云端软删除。
//
// 第二个返回值说「云端这个 id 之后不要再拉回来」。
func archiveOne(ctx context.Context, client *apiclient.Client, state *syncstate.State,
	entry syncstate.Entry, options SyncOptions) (syncResult, bool) {

	if options.DryRun {
		return syncResult{Name: entry.Name, Path: entry.Path,
			Outcome: WouldDelete,
			Detail:  "本地已删除，演练：云端会软删除（回收期内可还原）"}, true
	}
	err := client.ArchiveSkill(ctx, entry.SkillID)
	switch {
	case err == nil:
		state.Remove(entry.Name, entry.Agent)
		return syncResult{Name: entry.Name, Path: entry.Path,
			Outcome: Deleted, SkillID: entry.SkillID,
			Detail: "本地已删除，云端已软删除（回收期内可 cloud restore 找回）"}, true
	case apiclient.CodeOf(err) == "NOT_FOUND":
		// 云端已经没了（别的机器删过）：把本机基线清掉，别再试。
		state.Remove(entry.Name, entry.Agent)
		return syncResult{Name: entry.Name, Path: entry.Path,
			Outcome: Skipped, SkillID: entry.SkillID,
			Detail: "本地已删除，云端已经没有这个 Skill，基线一并清理"}, true
	default:
		return syncResult{Name: entry.Name, Path: entry.Path,
			Outcome: Failed, SkillID: entry.SkillID,
			Detail: "云端删除失败: " + err.Error()}, false
	}
}

// removalCandidates 挑出「本地删掉了、该往云端传删除」的基线条目。
//
// 判定的是「本地删掉了」，不是「本地扫不到」——两者差着一大步，差的那一步
// 就是别人云端上的东西：
//
//   - 基线里得记着云端 Skill 的 id：没记的说明它不是从这台机器传上去的，
//     云端没有对应的东西可删（别把别人的 Skill 删了）。
//   - 基线记的路径确实没了。
//   - 本机别处没有同一份：同一个 Skill 装在多个 Agent 目录下，
//     或者挪到了别的扫描目录，都不算删。
//   - 还在本次扫描范围内：改了 Agent 目录、删了自定义目录之后，
//     老路径扫不到不代表用户删了它。
func removalCandidates(state syncstate.State, scanned []SyncTarget, roots []scanRoot,
	options SyncOptions) []syncstate.Entry {

	present := newPresentSet(scanned)
	var candidates []syncstate.Entry
	for _, entry := range state.Skills {
		if entry.SkillID == "" {
			continue
		}
		if options.Only != "" && entry.Name != options.Only {
			continue
		}
		if present.hasPath(entry.Path) {
			continue // 这份文件还在
		}
		if present.hasID(entry.SkillID) {
			continue // 同一个云端 Skill 本机别处还装着一份
		}
		if sameNameOtherCopy(entry, scanned) {
			continue
		}
		if !entryRemoved(entry, roots) {
			continue
		}
		candidates = append(candidates, entry)
	}
	return candidates
}

// presentSet 是「本机现在还有什么」的快照。
//
// 按实体路径记，不按名字：多个 Agent 共用一个技能目录时（user_skills_dir
// 填同一个路径，或目录是指到同一处的链接），同名的两条基线指向同一份文件，
// 按名字判会把「共享目录里删掉这一份」当成「别处还有」而漏传删除。
type presentSet struct {
	paths map[string]bool
	ids   map[string]bool
}

// newPresentSet 按扫到的目标建快照。
func newPresentSet(scanned []SyncTarget) presentSet {
	present := presentSet{paths: map[string]bool{}, ids: map[string]bool{}}
	for _, target := range scanned {
		present.paths[realSkillPath(target.Path)] = true
		if target.SkillID != "" {
			present.ids[target.SkillID] = true
		}
	}
	return present
}

// hasPath 这份文件还在吗。
func (p presentSet) hasPath(path string) bool {
	if path == "" {
		return false
	}
	return p.paths[realSkillPath(path)]
}

// hasID 这个云端 Skill 本机别处还装着一份吗。
func (p presentSet) hasID(id string) bool {
	return id != "" && p.ids[id]
}

// entryRemoved 判断基线记的那个目录是不是真的没了。
//
// 老基线没记路径时重新扫一遍这个 Agent 的目录；路径不在扫描范围内
// （改了 Agent 目录、删了自定义目录）也不算删除——扫不到不等于用户删了它。
func entryRemoved(entry syncstate.Entry, roots []scanRoot) bool {
	path := entry.Path
	if path == "" {
		dir, err := agent.Dir(Cfg, entry.Agent, "user", "")
		if err != nil {
			return false // 这个 Agent 现在不扫了：不算删除
		}
		if _, err := os.Stat(dir); err != nil {
			return false // 扫描根整个没了：多半是路径变了，不是用户删的
		}
		path = localSkillPath(entry)
		if path != "" {
			return false // 还在
		}
	} else if !inScope(path, roots) {
		return false
	}
	_, err := os.Stat(path)
	return err != nil
}

// sameNameOtherCopy 判断本机别处是不是还有「同名但不是同一份」的副本。
//
// 云端可能有两个同名但 id 不同的 Skill（删过又重建），本机只装了其中一个。
// 少的那份不代表用户不要了——按 id 比对会把它当成「别处没有」而误删云端那份。
//
// 比的是实体路径：共用一个技能目录时，同名的两份就是同一份文件，不算「别处还有」。
func sameNameOtherCopy(entry syncstate.Entry, scanned []SyncTarget) bool {
	for _, target := range scanned {
		if target.Name != entry.Name {
			continue
		}
		if entry.Path == "" {
			// 老基线没记路径：同名就当别处还有，保守处理。
			return true
		}
		if realSkillPath(target.Path) != realSkillPath(entry.Path) {
			return true
		}
	}
	return false
}

// inScope 这条基线还在本次扫描范围内吗？
//
// 范围变了（改了 Agent 目录、删了自定义目录）时不能把「扫不到了」当成
// 「用户删掉了」——配置一改就清空云端，代价太大。
// 扫描根整个没了（网盘没挂上、目录被搬走）同样不算删除。
func inScope(path string, roots []scanRoot) bool {
	for _, root := range roots {
		if !pathWithin(root.dir, path) {
			continue
		}
		if _, err := os.Stat(root.dir); err != nil {
			continue
		}
		return true
	}
	return false
}

// pathWithin target 在 root 底下吗。
func pathWithin(root, target string) bool {
	rel, err := filepath.Rel(root, target)
	if err != nil {
		return false
	}
	return rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}
