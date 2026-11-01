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
	// 目录临时不可用时，扫不到东西不等于用户删了它：
	// 这种根下面的删除不往云端传（设计文档 §3.6）。
	// 用户确认过（--force-delete）就照传。
	blind := blindRoots(*state, roots)
	var results []syncResult
	dropping := map[string]bool{}
	archived := map[string]bool{}
	for _, entry := range candidates {
		if !options.ForceDelete && rootBlind(entry, roots, blind) {
			results = append(results, syncResult{Name: entry.Name, Path: entry.Path,
				Outcome: PendingDelete,
				Detail:  "本地目录看起来整个没了（可能临时不可用），没往云端传删除；确认目录真被清空了用 --force-delete --propagate-delete，或用 skillmesh cloud delete"})
			continue
		}
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

// blindRoots 找出「现在不能当作空目录看待」的扫描根。
//
// 目录临时不可用时，扫不到东西不等于用户删了它们：照着「全没了」往云端
// 传删除，一次就能把整个目录的 Skill 从云端清掉（设计文档 §3.6
// 「不应将无法访问目录当作空目录并据此同步删除」）。
//
// 两种情况算「看不清」：
//
//   - 目录列不出来：权限变了、路径被换成了文件。东西还在，只是看不见。
//   - 目录里一个 Skill 都不剩，而基线里本来有：整个目录被搬走、挂载点掉了。
//     用户删东西是一个个删的，不会一次全没。
//
// 代价是「用户真的把整个目录清空了」也传不上去——那种情况云端留着，
// 本机下一轮又会拉回来。宁可云端多留着，也别一次把用户的云端清空。
func blindRoots(state syncstate.State, roots []scanRoot) map[string]bool {
	blind := map[string]bool{}
	for _, root := range roots {
		if _, err := os.ReadDir(root.dir); err != nil {
			blind[root.dir] = true
			continue
		}
		if len(localSkills(root.dir, root.recursive)) > 0 {
			continue // 目录里还有 Skill：不是整个目录没了
		}
		if rootHadSkills(state, root) {
			blind[root.dir] = true
		}
	}
	return blind
}

// rootHadSkills 这个扫描根的基线里原来有没有 Skill。
func rootHadSkills(state syncstate.State, root scanRoot) bool {
	for _, entry := range state.Skills {
		if entry.Path != "" {
			if pathWithin(root.dir, entry.Path) {
				return true
			}
			continue
		}
		// 老基线没记路径：按 Agent 目录认。
		if dir, err := agent.Dir(Cfg, entry.Agent, "user", ""); err == nil && dir == root.dir {
			return true
		}
	}
	return false
}

// rootBlind 这个条目所在的扫描根是不是现在看不清的。
func rootBlind(entry syncstate.Entry, roots []scanRoot, blind map[string]bool) bool {
	if entry.Path == "" {
		dir, err := agent.Dir(Cfg, entry.Agent, "user", "")
		if err != nil {
			return false
		}
		return blind[dir]
	}
	root, ok := rootOf(entry.Path, roots)
	if !ok {
		return false
	}
	return blind[root.dir]
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
		if !entryRemoved(entry, roots, options.ForceDelete) {
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
//
// force 是用户确认过「目录真的被清空了」：那时扫描根整个没了也算删除，
// 不然整目录删掉这件事永远传不上去。
func entryRemoved(entry syncstate.Entry, roots []scanRoot, force bool) bool {
	path := entry.Path
	if path == "" {
		dir, err := agent.Dir(Cfg, entry.Agent, "user", "")
		if err != nil {
			return false // 这个 Agent 现在不扫了：不算删除
		}
		if _, err := os.Stat(dir); err != nil {
			// 扫描根整个没了：多半是路径变了，不是用户删的。
			return force
		}
		path = localSkillPath(entry)
		if path != "" {
			return false // 还在
		}
	} else if !inScope(path, roots, force) {
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
//
// force 是用户确认过「目录真的被清空了」：那时连扫描根整个没了都算范围内。
func inScope(path string, roots []scanRoot, force bool) bool {
	if force {
		for _, root := range roots {
			if pathWithin(root.dir, path) {
				return true
			}
		}
		return false
	}
	_, ok := rootOf(path, roots)
	return ok
}

// rootOf 找 path 所在的扫描根。找不到就是不在扫描范围内。
func rootOf(path string, roots []scanRoot) (scanRoot, bool) {
	for _, root := range roots {
		if !pathWithin(root.dir, path) {
			continue
		}
		if _, err := os.Stat(root.dir); err != nil {
			continue
		}
		return root, true
	}
	return scanRoot{}, false
}

// pathWithin target 在 root 底下吗。
func pathWithin(root, target string) bool {
	rel, err := filepath.Rel(root, target)
	if err != nil {
		return false
	}
	return rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}
