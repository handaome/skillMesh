package cmd

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/installer"
	"github.com/handaome/skillmesh/apps/cli/internal/packager"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/handaome/skillmesh/apps/cli/internal/syncstate"
)

// fileAction 是单个文件该怎么处理（设计文档 §6.3）。
type fileAction string

const (
	// actionKeepLocal 本机改了、云端没动 → 保留本机。
	actionKeepLocal fileAction = "保留本机"
	// actionTakeCloud 云端改了、本机没动 → 用云端的。
	actionTakeCloud fileAction = "采用云端"
	// actionMergeLocal 同一文件两边都改 → 三方文本合并。
	actionMergeLocal fileAction = "三方合并"
	// actionConflict 同一文件两边改到同一处且合不了 → 冲突。
	actionConflict fileAction = "冲突"
	// actionDeleted 本机删掉了、云端还在 → 删除待确认，不自动拉回。
	actionDeleted fileAction = "本机已删除"
)

// mergePlan 逐文件决定怎么合。这是纯函数：只看三份摘要，不碰磁盘和网络。
//
// base 是上次同步时的摘要，local 是本机现状，cloud 是云端现状。
// 本机删掉、云端还在的文件不进合并结果——删除传播要人确认（§6.3 最后一行），
// 它们被标成「本机已删除」，由人来定夺。
func mergePlan(base, local, cloud map[string]string) map[string]fileAction {
	plan := map[string]fileAction{}
	for path, cloudSum := range cloud {
		localSum, inLocal := local[path]
		baseSum, hadBase := base[path]
		switch {
		case !inLocal && hadBase:
			// 本机删掉的文件：不从云端拉回来。删除要不要传到云端得人确认
			// （设计文档 §6.3：本地删除 → 删除待确认/回收流程）。
			plan[path] = actionDeleted
		case !inLocal:
			// 云端新增的文件：拉下来
			plan[path] = actionTakeCloud
		case hadBase && localSum == baseSum && cloudSum != baseSum:
			// 只有云端动过
			plan[path] = actionTakeCloud
		case hadBase && localSum != baseSum && cloudSum == baseSum:
			// 只有本机动过
			plan[path] = actionKeepLocal
		case hadBase && localSum != baseSum && cloudSum != baseSum:
			if localSum == cloudSum {
				continue // 两边改成一样，不用管
			}
			plan[path] = actionMergeLocal
		default:
			// 没有基线：两边都只能算「改过」，只能试文本合并
			if localSum == cloudSum {
				continue
			}
			plan[path] = actionMergeLocal
		}
	}
	return plan
}

// mergeConflict 按文件合并一次冲突（设计文档 §6.3）。
//
//	本地改了 A、云端改了 B      → 两边都要，合完直接上传
//	同一个文件两边都改且能文本合 → 合完上传
//	同一个文件两边都改且合不了   → 返回 ok=false，交给 --resolve 生成冲突副本
func mergeConflict(ctx context.Context, client *apiclient.Client, target syncTarget,
	entry syncstate.Entry, local packager.Artifact, skill apiclient.Skill) (bool, string, error) {

	localFiles, err := skillscan.DirHashes(target.Path)
	if err != nil {
		return false, "读不出本机文件清单", err
	}
	cloudFiles, artifactID, err := cloudManifest(ctx, client, skill.ID)
	if err != nil {
		return false, "读不出云端文件清单", err
	}
	if len(entry.BaseFiles) == 0 {
		return false, "本机没有上次同步的基线，按文件合不了", nil
	}
	hashes := toHashMap(localFiles)
	plan := mergePlan(entry.BaseFiles, hashes, cloudFiles)

	// 云端独有的内容先解到临时目录，否则合并结果会丢掉它们
	cloudDir, cleanup, err := materialiseCloud(ctx, client, artifactID)
	if err != nil {
		return false, "拉取云端内容失败", err
	}
	defer cleanup()

	var merged, conflicted, deleted []string
	for path, action := range plan {
		switch action {
		case actionTakeCloud:
			if err := copyFile(filepath.Join(cloudDir, filepath.FromSlash(path)),
				filepath.Join(target.Path, filepath.FromSlash(path))); err != nil {
				return false, "写入云端版本失败：" + path, nil
			}
			merged = append(merged, path)
		case actionMergeLocal:
			ok, err := mergeFile(entry.BaseSnapshot, target.Path, cloudDir, path)
			if err != nil {
				return false, fmt.Sprintf("三方合并失败 %s：%v", path, err), nil
			}
			if ok {
				merged = append(merged, path)
			} else {
				conflicted = append(conflicted, path)
			}
		case actionDeleted:
			// 本机删掉的：不拉回来，交给人确认删除要不要传上去。
			deleted = append(deleted, path)
		}
	}
	if len(conflicted) > 0 || len(deleted) > 0 {
		var notes []string
		if len(conflicted) > 0 {
			notes = append(notes, fmt.Sprintf("%d 个文件两边改到同一处合不了：%v",
				len(conflicted), conflicted))
		}
		if len(deleted) > 0 {
			notes = append(notes, fmt.Sprintf("%d 个文件本机已删除、云端还在，"+
				"删除要不要传到云端得你确认：%v", len(deleted), deleted))
		}
		return false, strings.Join(notes, "；"), nil
	}
	return true, fmt.Sprintf("按文件合并完成（云端 %d 个文件参与）", len(merged)), nil
}

// mergeFile 对单个文件做三方文本合并。合不了就返回 false，不写盘。
func mergeFile(baseSnapshot, localDir, cloudDir, path string) (bool, error) {
	localPath := filepath.Join(localDir, filepath.FromSlash(path))
	cloudPath := filepath.Join(cloudDir, filepath.FromSlash(path))
	localData, err := os.ReadFile(localPath)
	if err != nil {
		return false, err
	}
	cloudData, err := os.ReadFile(cloudPath)
	if err != nil {
		return false, err
	}
	baseData, err := readSnapshot(baseSnapshot, path)
	if err != nil {
		// 基线副本读不到就合不了，不能拿「没有基线」当错误往上抛。
		return false, nil
	}
	merged, ok := skillscan.Merge3(baseData, localData, cloudData)
	if !ok {
		return false, nil
	}
	return true, os.WriteFile(localPath, merged, 0o644)
}

// readSnapshot 从上次同步的本地副本里取一个文件。
func readSnapshot(snapshot, path string) ([]byte, error) {
	if snapshot == "" {
		return nil, nil
	}
	return os.ReadFile(filepath.Join(snapshot, filepath.FromSlash(path)))
}

// materialiseCloud 把云端最新版本解到临时目录，返回目录与清理函数。
func materialiseCloud(ctx context.Context, client *apiclient.Client, artifactID string) (string, func(), error) {
	if artifactID == "" {
		return "", func() {}, nil
	}
	data, err := client.DownloadArtifact(ctx, artifactID)
	if err != nil {
		return "", func() {}, err
	}
	work, err := os.MkdirTemp("", "skillmesh-merge-*")
	if err != nil {
		return "", func() {}, err
	}
	dir, err := installer.ExtractZip(data, work)
	if err != nil {
		os.RemoveAll(work)
		return "", func() {}, err
	}
	return dir, func() { os.RemoveAll(work) }, nil
}

// cloudManifest 取云端最新已发布版本的文件摘要表和它的制品 ID。
func cloudManifest(ctx context.Context, client *apiclient.Client, skillID string) (map[string]string, string, error) {
	versions, err := client.Versions(ctx, skillID)
	if err != nil {
		return nil, "", err
	}
	selected, err := pickVersion(versions, "", false, lockfileEntry(syncTarget{SkillID: skillID}), false)
	if err != nil {
		return nil, "", err
	}
	files := map[string]string{}
	for _, entry := range selected.Manifest {
		files[entry.Path] = entry.SHA256
	}
	artifactID := ""
	if selected.Artifact != nil {
		artifactID = selected.Artifact.ID
	}
	return files, artifactID, nil
}

func toHashMap(files []skillscan.FileDigest) map[string]string {
	hashes := make(map[string]string, len(files))
	for _, file := range files {
		hashes[file.Path] = file.SHA256
	}
	return hashes
}

func copyFile(from, to string) error {
	data, err := os.ReadFile(from)
	if err != nil {
		return err
	}
	return os.WriteFile(to, data, 0o644)
}
