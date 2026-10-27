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

// loadStateForSync 载入同步基线，并把云端的删除落到本地。
//
// 删除要先于对账处理：本地那份先移走，
// 否则它会被当成「云端没有、本地有」又传回云端。
//
// 第二个返回值是「本次是否成功拿到云端的已删除清单」：
// 拿不到就不能上传——不知道云端删了什么，上传会把删掉的 Skill 复活。
func loadStateForSync(ctx context.Context, client *apiclient.Client,
	options SyncOptions) (syncstate.State, []syncResult, bool, error) {

	state, err := syncstate.Load(Cfg.ConfigDir)
	if err != nil {
		return syncstate.State{}, nil, false, err
	}
	results, known := applyCloudDeletes(ctx, client, &state, options)
	return state, results, known, nil
}

// applyCloudDeletes 把云端删掉的 Skill 同步到本地，并保存基线。
func applyCloudDeletes(ctx context.Context, client *apiclient.Client,
	state *syncstate.State, options SyncOptions) ([]syncResult, bool) {

	results, known, err := propagateCloudDeletes(ctx, client, state, options)
	if err != nil {
		results = append(results, syncResult{Name: "云端删除同步",
			Outcome: Failed, Detail: describeListError(err)})
	}
	if len(results) > 0 {
		if err := state.Save(Cfg.ConfigDir); err != nil {
			results = append(results, syncResult{Name: "同步状态",
				Outcome: Failed, Detail: "保存同步状态失败: " + err.Error()})
		}
	}
	return results, known
}

// describeListError 说清「拉不到已删除清单」意味着什么。
//
// 404 多半是服务端还没升级：不带这个说明，用户只会看到一句干巴巴的
// 404，猜不到自己得先升服务端。
func describeListError(err error) string {
	if apiclient.CodeOf(err) == "NOT_FOUND" {
		return "服务端没有 /deleted-skills 接口（版本太旧）：" + err.Error() +
			"；请升级服务端，为免把云端删掉的 Skill 复活，本次不做上传"
	}
	return "拉不到云端已删除清单：" + err.Error() +
		"；为免把云端删掉的 Skill 复活，本次不做上传"
}

// propagateCloudDeletes 把云端删掉的 Skill 在本地也删掉。
//
// 只认服务端明确记下的删除：靠「云端查不到」推断会把换服务器导致的
// 空库误当成删除，把本机内容全清掉（syncstate 里踩过的坑）。
//
// 删掉的东西进本机回收站，不直接抹掉：同步可能跑在后台，
// 误删要能找回来。
//
// 第二个返回值：本次是否成功拿到云端的已删除清单。
func propagateCloudDeletes(ctx context.Context, client *apiclient.Client,
	state *syncstate.State, options SyncOptions) ([]syncResult, bool, error) {

	// 本次只下载：不上传，不用管删除清单。
	if options.Direction == "download" {
		return nil, true, nil
	}
	deleted, err := client.DeletedSkills(ctx)
	if err != nil {
		return nil, false, err
	}
	if len(deleted) == 0 {
		return nil, true, nil
	}
	tombstones := map[string]apiclient.DeletedSkill{}
	for _, item := range deleted {
		tombstones[item.ID] = item
	}
	// 本次只上传：本地不删，但清单还是要——上传前得知道云端删了什么。
	if options.Direction == "upload" {
		return nil, true, nil
	}
	var results []syncResult
	for _, entry := range state.Skills {
		tomb, ok := tombstones[entry.SkillID]
		if entry.SkillID == "" || !ok {
			continue
		}
		// 云端删完又用同一个 slug 重建了一个：那边有活的就别动本地，
		// 本地那份继续跟新的那个对账。
		alive, err := cloudStillHas(ctx, client, tomb.Slug)
		if err != nil {
			results = append(results, syncResult{Name: entry.Name, Path: entry.Path,
				Outcome: Failed, Detail: "查云端状态失败: " + err.Error()})
			continue
		}
		if alive {
			continue
		}
		if options.DryRun {
			// 演练只报，不动本地。
			results = append(results, syncResult{Name: entry.Name, Path: entry.Path,
				Outcome: Skipped, Detail: "云端已删除，演练：本地将被移入回收站"})
			continue
		}
		result, err := deleteLocalCopy(entry)
		if err != nil {
			results = append(results, syncResult{Name: entry.Name, Path: entry.Path,
				Outcome: Failed, Detail: err.Error()})
			continue
		}
		// 基线一起清掉：留着它，下一轮会把删掉的东西又传回云端。
		state.Remove(entry.Name, entry.Agent)
		results = append(results, result)
	}
	return results, true, nil
}

// cloudStillHas 云端这个 slug 下是不是还有活着的 Skill。
func cloudStillHas(ctx context.Context, client *apiclient.Client, slug string) (bool, error) {
	_, found, err := client.FindSkillBySlug(ctx, slug)
	if err != nil {
		return false, err
	}
	return found, nil
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
