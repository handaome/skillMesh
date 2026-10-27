package cmd

import (
	"context"
	"fmt"
	"os"
	"path/filepath"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/handaome/skillmesh/apps/cli/internal/syncstate"
	"github.com/handaome/skillmesh/apps/cli/internal/trash"
)

// cloudState 是本次同步看到的服务端状态。
type cloudState struct {
	// Deletes 是云端已删除（归档）的 Skill，按 slug 索引。
	Deletes map[string]apiclient.DeletedSkill
	// Known 是清单拿到了没有。拿不到就不能上传：
	// 不知道云端删了什么，上传会把删掉的 Skill 复活。
	Known bool
	// ListError 是拉清单时的错误，只有拿不到时才有。
	ListError error
}

// loadStateForSync 载入同步基线，并拉取云端的已删除清单。
//
// 清单按 slug 索引：本地这份是哪个 Skill，只能靠 slug 认。
// 基线里记的是 skill_id，但基线可能压根没有这条记录——
// 别的机器上装的、刚复制进来的，基线里都是空的。
func loadStateForSync(ctx context.Context, client *apiclient.Client,
	options SyncOptions) (syncstate.State, cloudState, error) {

	state, err := syncstate.Load(Cfg.ConfigDir)
	if err != nil {
		return syncstate.State{}, cloudState{}, err
	}
	// 本次只下载：不上传，不用管删除清单。
	if options.Direction == "download" {
		return state, cloudState{Known: true}, nil
	}
	deleted, err := client.DeletedSkills(ctx)
	if err != nil {
		return state, cloudState{ListError: err}, nil
	}
	index := map[string]apiclient.DeletedSkill{}
	for _, item := range deleted {
		index[item.Slug] = item
	}
	return state, cloudState{Deletes: index, Known: true}, nil
}

// describeListError 说清「拉不到已删除清单」意味着什么。
//
// 404 多半是服务端还没升级：不带这个说明，用户只会看到一句干巴巴的
// 404，猜不到自己得先升服务端。
func describeListError(err error) string {
	if err == nil {
		return "拉不到云端已删除清单；为免把云端删掉的 Skill 复活，本次不做上传"
	}
	if apiclient.CodeOf(err) == "NOT_FOUND" {
		return "服务端没有 /deleted-skills 接口（版本太旧）：" + err.Error() +
			"；请升级服务端，为免把云端删掉的 Skill 复活，本次不做上传"
	}
	return "拉不到云端已删除清单：" + err.Error() +
		"；为免把云端删掉的 Skill 复活，本次不做上传"
}

// applyCloudDeletes 处理云端删掉的 Skill：本地那份跟着删掉，
// 或者至少不再往云端上传。
//
// 两件事分开做，因为它们针对的不是同一个本地副本：
//
//   - 本地这份就是被删的那个（基线里的 skill_id 与删除记录对得上）：
//     它已经没有对应的云端 Skill 了，留着只会让人以为还能用。移进本机回收站。
//   - 对不上（基线里没记过，或记的是别的 Skill）：那是另一台机器上的副本，
//     或者用户自己放的目录。不动它，但**绝不能上传**——
//     云端明确删过，上传就是复活。
//
// 返回处理结果，以及剩下的待同步目标（被拦下的不在其中）。
func applyCloudDeletes(ctx context.Context, state *syncstate.State, targets []SyncTarget,
	options SyncOptions) ([]syncResult, []SyncTarget) {

	if len(options.CloudDeletes) == 0 {
		return nil, targets
	}
	var results []syncResult
	kept := make([]SyncTarget, 0, len(targets))
	for _, target := range targets {
		tomb, ok := options.CloudDeletes[slugOf(target)]
		if !ok {
			kept = append(kept, target)
			continue
		}
		entry, known := state.Find(target.Name, target.Agent)
		// 本地这份是不是被删掉的那个：基线里记的 id 与删除记录一致才算。
		mine := known && entry.SkillID != "" && entry.SkillID == tomb.ID
		if !mine {
			results = append(results, syncResult{Name: target.Name, Path: target.Path,
				Outcome: Skipped,
				Detail:  "云端已删除这个 Skill，本机这份不与之对账，也不上传（避免复活）"})
			continue
		}
		if options.DryRun {
			results = append(results, syncResult{Name: target.Name, Path: target.Path,
				Outcome: WouldDelete, Detail: "云端已删除，演练：本地将被移入回收站"})
			continue
		}
		result, err := deleteLocalCopy(entry)
		if err != nil {
			results = append(results, syncResult{Name: target.Name, Path: target.Path,
				Outcome: Failed, Detail: err.Error()})
			continue
		}
		// 基线一起清掉：留着它，下一轮会把删掉的东西又传回云端。
		state.Remove(entry.Name, entry.Agent)
		results = append(results, result)
	}
	if len(results) > 0 && !options.DryRun {
		if err := state.Save(Cfg.ConfigDir); err != nil {
			results = append(results, syncResult{Name: "同步状态",
				Outcome: Failed, Detail: "保存同步状态失败: " + err.Error()})
		}
	}
	return results, kept
}

// slugOf 算本地这份在云端对应的 slug：目录名转的，与发布时用的是同一套规则。
func slugOf(target SyncTarget) string {
	slug, err := skillscan.SlugFromDirName(filepath.Base(target.Path))
	if err != nil {
		return ""
	}
	return slug
}

// deleteLocalCopy 把本地那份移进本机回收站。
//
// 本地目录已经不在了（用户自己删过）也算删成功：只剩基线要清。
func deleteLocalCopy(entry syncstate.Entry) (syncResult, error) {
	path := entry.Path
	if path == "" {
		path = localSkillPath(entry)
	}
	if path == "" {
		return syncResult{Name: entry.Name, Outcome: Deleted, SkillID: entry.SkillID,
			Detail: "云端已删除，本地已无此目录"}, nil
	}
	if _, err := os.Stat(path); err != nil {
		return syncResult{Name: entry.Name, Path: path, Outcome: Deleted, SkillID: entry.SkillID,
			Detail: "云端已删除，本地已无此目录"}, nil
	}
	item, err := moveToTrash(entry.Name, path)
	if err != nil {
		return syncResult{}, fmt.Errorf("移入回收站失败: %w", err)
	}
	return syncResult{Name: entry.Name, Path: path, Outcome: Deleted, SkillID: entry.SkillID,
		Detail: "云端已删除，本地已移入回收站 " + item.Entry}, nil
}

// localSkillPath 在授权目录里找出这个 Skill 的本地路径。
//
// 老的同步状态里没记路径，只能重新扫一遍。
func localSkillPath(entry syncstate.Entry) string {
	dir, err := agent.Dir(Cfg, entry.Agent, "user", "")
	if err != nil {
		return ""
	}
	for _, path := range localSkills(dir, true) {
		if skillscan.SkillName(path) == entry.Name {
			return path
		}
	}
	return ""
}

// moveToTrash 把目录移进本机回收站。
//
// 跨盘时 os.Rename 搬不动：先拷进配置目录再移，原件随后删掉。
// 拷贝不是搬：搬走会先把原件弄没了，中途出错就两头不落。
func moveToTrash(name, path string) (trash.Item, error) {
	store := trash.NewStore(Cfg.ConfigDir)
	item, err := store.Move(name, path)
	if err == nil {
		return item, nil
	}
	if err := os.MkdirAll(Cfg.ConfigDir, 0o755); err != nil {
		return trash.Item{}, err
	}
	staging, err := os.MkdirTemp(Cfg.ConfigDir, "skillmesh-trash-*")
	if err != nil {
		return trash.Item{}, err
	}
	defer os.RemoveAll(staging)
	copied := filepath.Join(staging, name)
	if err := copyDir(path, copied); err != nil {
		return trash.Item{}, fmt.Errorf("备份失败: %w", err)
	}
	// 从同盘移进回收站：os.Rename 跨盘搬不动。
	item, err = store.Move(name, copied)
	if err != nil {
		return trash.Item{}, err
	}
	if err := os.RemoveAll(path); err != nil {
		return trash.Item{}, err
	}
	return item, nil
}
