package cmd

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"sort"
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
	// ActionKeepLocal 本机改了、云端没动 → 保留本机。
	ActionKeepLocal fileAction = "保留本机"
	// ActionTakeCloud 云端改了、本机没动 → 用云端的。
	ActionTakeCloud fileAction = "采用云端"
	// ActionMergeLocal 同一文件两边都改 → 三方文本合并。
	ActionMergeLocal fileAction = "三方合并"
	// ActionConflict 同一文件两边改到同一处且合不了 → 冲突。
	ActionConflict fileAction = "冲突"
	// ActionDeleted 本机删掉了、云端还在 → 删除待确认，不自动拉回。
	ActionDeleted fileAction = "本机已删除"
)

// MergePlan 逐文件决定怎么合。这是纯函数：只看三份摘要，不碰磁盘和网络。
//
// base 是上次同步时的摘要，local 是本机现状，cloud 是云端现状。
// 本机删掉、云端还在的文件不进合并结果——删除传播要人确认（§6.3 最后一行），
// 它们被标成「本机已删除」，由人来定夺。
func MergePlan(base, local, cloud map[string]string) map[string]fileAction {
	plan := map[string]fileAction{}
	for path, cloudSum := range cloud {
		localSum, inLocal := local[path]
		baseSum, hadBase := base[path]
		switch {
		case !inLocal && hadBase:
			// 本机删掉的文件：不从云端拉回来。删除要不要传到云端得人确认
			// （设计文档 §6.3：本地删除 → 删除待确认/回收流程）。
			plan[path] = ActionDeleted
		case !inLocal:
			// 云端新增的文件：拉下来
			plan[path] = ActionTakeCloud
		case hadBase && localSum == baseSum && cloudSum != baseSum:
			// 只有云端动过
			plan[path] = ActionTakeCloud
		case hadBase && localSum != baseSum && cloudSum == baseSum:
			// 只有本机动过
			plan[path] = ActionKeepLocal
		case hadBase && localSum != baseSum && cloudSum != baseSum:
			if localSum == cloudSum {
				continue // 两边改成一样，不用管
			}
			plan[path] = ActionMergeLocal
		default:
			// 没有基线：两边都只能算「改过」，只能试文本合并
			if localSum == cloudSum {
				continue
			}
			plan[path] = ActionMergeLocal
		}
	}
	return plan
}

// MergeConflict 按文件合并一次冲突（设计文档 §6.3）。
//
//	本地改了 A、云端改了 B      → 两边都要，合完直接上传
//	同一个文件两边都改且能文本合 → 合完上传
//	同一个文件两边都改且合不了   → 返回 ok=false，交给 --resolve 生成冲突副本
//
// 合并结果先落在临时文件上，全部合成了才写回本机目录：
// 中途失败不能留下「改了一半」的目录——基线没更新，下一轮又判冲突。
//
// 只写合并涉及的文件，不整目录换入：整目录换入会把本机目录里的符号链接
// 一并换掉（CopyTree 只拷普通文件），也会把整个目录拷两遍——
// 带 .git 的大目录能明显变慢。本机目录里没被碰过的文件原样留着。
func MergeConflict(ctx context.Context, client *apiclient.Client, target SyncTarget,
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
	hashes := ToHashMap(localFiles)
	plan := MergePlan(entry.BaseFiles, hashes, cloudFiles)

	// 云端独有的内容先解到临时目录，否则合并结果会丢掉它们
	cloudDir, cleanup, err := materialiseCloud(ctx, client, artifactID)
	if err != nil {
		return false, "拉取云端内容失败", err
	}
	defer cleanup()

	// 合并结果先写到临时文件：算完再写回本机目录。
	staged, err := stageMerged(target.Path, cloudDir, entry.BaseSnapshot, plan)
	if err != nil {
		return false, err.Error(), err
	}
	defer os.RemoveAll(staged.staged)

	if len(staged.conflicted) > 0 || len(staged.deleted) > 0 {
		var notes []string
		if len(staged.conflicted) > 0 {
			// 冲突副本落到 Skill 目录旁的隐藏目录：内容带着冲突标记，
			// 用户能直接看到两边各改成什么样（设计文档 §6.3「生成冲突副本」）。
			conflictDir, err := saveConflictCopies(target.Path, staged)
			if err != nil {
				return false, "写冲突副本失败：" + err.Error(), err
			}
			notes = append(notes, fmt.Sprintf("%d 个文件两边改到同一处合不了，"+
				"冲突副本在 %s：%v",
				len(staged.conflicted), conflictDir, staged.conflicted))
		}
		if len(staged.deleted) > 0 {
			notes = append(notes, fmt.Sprintf("%d 个文件本机已删除、云端还在，"+
				"删除要不要传到云端得你确认（--allow-delete）：%v",
				len(staged.deleted), staged.deleted))
		}
		return false, strings.Join(notes, "；"), nil
	}
	// 全合上了：把合并涉及的文件写回本机目录。单个文件走「写临时文件再改名」，
	// 中途出错最坏是这次合并没写完——下次同步会重新判冲突，不会留下半截内容。
	if err := writeBack(target.Path, staged); err != nil {
		return false, "写回本机目录失败：" + err.Error(), err
	}
	return true, fmt.Sprintf("按文件合并完成（云端 %d 个文件参与）", len(staged.merged)), nil
}

// mergeResult 是一次合并的结果。
type mergeResult struct {
	// merged 是合上的文件，暂存副本在 staged 目录里，路径与本机目录一致。
	merged []string
	// conflicted 是两边改到同一处、合不了的文件。
	conflicted []string
	// deleted 是本机删掉、云端还在的文件。
	deleted []string
	// modes 是各文件该有的权限位：云端那份的可执行位要带过来。
	modes map[string]os.FileMode
	// staged 是临时目录，合并结果都在里面。
	staged string
}

// stageMerged 算出合并结果，写进临时目录。
//
// 本机目录不动：算的过程中出问题（合不了、读不出文件），
// 本机目录还是原样，基线没更新，下一轮会重新判冲突。
func stageMerged(localDir, cloudDir, baseSnapshot string, plan map[string]fileAction) (
	mergeResult, error) {

	result := mergeResult{modes: map[string]os.FileMode{}}
	work, err := os.MkdirTemp("", "skillmesh-merge-*")
	if err != nil {
		return result, err
	}
	result.staged = work
	cleanup := func() { os.RemoveAll(work) }

	for _, path := range sortedPaths(plan) {
		switch plan[path] {
		case ActionTakeCloud:
			// 云端独有或只有云端改过：直接用云端那份。
			src := filepath.Join(cloudDir, filepath.FromSlash(path))
			dst := filepath.Join(work, filepath.FromSlash(path))
			if err := copyFile(src, dst); err != nil {
				cleanup()
				return result, fmt.Errorf("写入云端版本失败 %s：%w", path, err)
			}
			// 权限跟着云端那份：云端的脚本是可执行的，本机装上也得能跑。
			if info, statErr := os.Stat(src); statErr == nil {
				result.modes[path] = info.Mode().Perm()
			}
			result.merged = append(result.merged, path)
		case ActionMergeLocal:
			merged, ok, mergeErr := MergeFile(baseSnapshot, localDir, cloudDir, path)
			if mergeErr != nil {
				cleanup()
				return result, fmt.Errorf("三方合并失败 %s：%w", path, mergeErr)
			}
			if !ok {
				// 合不了：内容带着冲突标记，先存进冲突副本目录。
				// 直接丢掉的话，用户只看到一句「合不了」，
				// 两边改了什么得自己去翻 git（设计文档 §6.3 要求生成冲突副本）。
				if len(merged) > 0 {
					dst := filepath.Join(work, "冲突", filepath.FromSlash(path))
					if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
						cleanup()
						return result, fmt.Errorf("写冲突副本失败 %s：%w", path, err)
					}
					if err := os.WriteFile(dst, merged, 0o644); err != nil {
						cleanup()
						return result, fmt.Errorf("写冲突副本失败 %s：%w", path, err)
					}
				}
				result.conflicted = append(result.conflicted, path)
				continue
			}
			// 合并结果先落暂存目录：全部合成了才写回本机目录。
			dst := filepath.Join(work, filepath.FromSlash(path))
			if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
				cleanup()
				return result, fmt.Errorf("暂存合并结果失败 %s：%w", path, err)
			}
			if err := os.WriteFile(dst, merged, 0o644); err != nil {
				cleanup()
				return result, fmt.Errorf("暂存合并结果失败 %s：%w", path, err)
			}
			result.merged = append(result.merged, path)
		case ActionDeleted:
			// 本机删掉的：不拉回来，交给人确认删除要不要传上去。
			result.deleted = append(result.deleted, path)
		}
	}
	return result, nil
}

// saveConflictCopies 把合不上的文件落成冲突副本，返回副本目录。
//
// 放在 Skill 目录旁的隐藏目录：隐藏目录不会被扫成 Skill，
// 也不会被当成「本地新增的 Skill」传上云端。
// 目录名带摘要，同一份内容反复冲突时不会覆盖上一次的副本。
func saveConflictCopies(skillPath string, staged mergeResult) (string, error) {
	if len(staged.conflicted) == 0 {
		return "", nil
	}
	source := filepath.Join(staged.staged, "冲突")
	// 没有可留的内容（比如基线副本读不到）：不建空目录。
	if _, err := os.Stat(source); err != nil {
		return "", nil
	}
	// 摘要取自冲突文件的清单：同一批冲突重试多少次，落到的都是同一个目录。
	sum := sha256.Sum256([]byte(strings.Join(staged.conflicted, "\n")))
	dest := filepath.Join(filepath.Dir(skillPath),
		"."+filepath.Base(skillPath)+".conflict-"+hex.EncodeToString(sum[:4]))
	if err := os.RemoveAll(dest); err != nil {
		return "", err
	}
	if err := copyDir(source, dest); err != nil {
		return "", err
	}
	return dest, nil
}

// writeBack 把合并结果写回本机目录。
//
// 写之前先备份原文件：写回中途失败（磁盘满、目录只读）时把备份还原，
// 本机目录回到合并前的样子。
//
// 不还原的话会留下更糟的状态：一半文件已是合并结果、另一半还是旧的，
// 而基线没更新——下一轮同步会把「已经合过的文件」判成冲突，
// 让用户去处理一个根本不存在的问题。
func writeBack(localDir string, result mergeResult) error {
	backup, err := backupFiles(localDir, result.merged)
	if err != nil {
		return err
	}
	if err := applyFiles(localDir, result); err != nil {
		restoreFiles(backup)
		return err
	}
	return nil
}

// backupFile 是一个文件的备份。
type backupFile struct {
	path    string
	data    []byte
	mode    os.FileMode
	existed bool
}

// fileBackup 是一批文件的备份。
type fileBackup struct {
	files []backupFile
}

// backupFiles 把要改的文件原样备份一份。
//
// 原来不存在的文件记 existed=false：还原时要删掉它，不能留个空壳。
//
// 备份放在内存里：要改的文件就那么几个，Skill 的文件通常几十 KB。
func backupFiles(localDir string, paths []string) (fileBackup, error) {
	backup := fileBackup{}
	for _, path := range paths {
		src := filepath.Join(localDir, filepath.FromSlash(path))
		entry := backupFile{path: src}
		info, statErr := os.Stat(src)
		if statErr == nil {
			entry.existed = true
			entry.mode = info.Mode().Perm()
			data, readErr := os.ReadFile(src)
			if readErr != nil {
				return fileBackup{}, readErr
			}
			entry.data = data
		}
		backup.files = append(backup.files, entry)
	}
	return backup, nil
}

// restoreFiles 把备份还原回去。
func restoreFiles(backup fileBackup) {
	for _, entry := range backup.files {
		if !entry.existed {
			os.Remove(entry.path)
			continue
		}
		tmp := entry.path + ".skillmesh-restore"
		if err := os.WriteFile(tmp, entry.data, entry.mode); err != nil {
			continue
		}
		os.Rename(tmp, entry.path)
	}
}

// applyFiles 把合并结果逐个写回本机目录。
func applyFiles(localDir string, result mergeResult) error {
	for _, path := range result.merged {
		src := filepath.Join(result.staged, filepath.FromSlash(path))
		dst := filepath.Join(localDir, filepath.FromSlash(path))
		if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
			return err
		}
		data, err := os.ReadFile(src)
		if err != nil {
			return err
		}
		// 权限：云端那份带的可执行位优先，本机原来的兜底。
		// 都没有就用 0644。
		mode := os.FileMode(0o644)
		if fromCloud, ok := result.modes[path]; ok {
			mode = fromCloud
		} else if info, statErr := os.Stat(dst); statErr == nil {
			mode = info.Mode().Perm()
		}
		// 临时文件用隐藏名：万一崩了留下残骸，扫描会跳过隐藏文件
		// （skillscan 与 packager 都跳），不会把它当成本机的文件传上云。
		tmp := filepath.Join(filepath.Dir(dst), "."+filepath.Base(dst)+".skillmesh-merge")
		// 先删掉上次的残骸：WriteFile 只在创建时用 perm，
		// 留着的旧文件会把旧权限位带过来。
		os.Remove(tmp)
		if err := os.WriteFile(tmp, data, mode); err != nil {
			return err
		}
		if err := os.Rename(tmp, dst); err != nil {
			os.Remove(tmp)
			return err
		}
	}
	return nil
}

// sortedPaths 按路径顺序处理合并计划：顺序固定，合并失败时才知道断在哪一步。
func sortedPaths(plan map[string]fileAction) []string {
	paths := make([]string, 0, len(plan))
	for path := range plan {
		paths = append(paths, path)
	}
	sort.Strings(paths)
	return paths
}

// MergeFile 对单个文件做三方文本合并。
//
// 合不了就返回 ok=false；合得了就返回合并后的内容，**不写盘**——
// 写盘由调用方决定（合并结果要先落到暂存目录，全部合成了才写回本机）。
func MergeFile(baseSnapshot, localDir, cloudDir, path string) (data []byte, ok bool, err error) {
	localPath := filepath.Join(localDir, filepath.FromSlash(path))
	cloudPath := filepath.Join(cloudDir, filepath.FromSlash(path))
	localData, err := os.ReadFile(localPath)
	if err != nil {
		return nil, false, err
	}
	cloudData, err := os.ReadFile(cloudPath)
	if err != nil {
		return nil, false, err
	}
	baseData, err := readSnapshot(baseSnapshot, path)
	if err != nil {
		// 基线副本读不到就合不了，不能拿「没有基线」当错误往上抛。
		return nil, false, nil
	}
	merged, ok := skillscan.Merge3(baseData, localData, cloudData)
	if !ok {
		// 合不了时把带冲突标记的内容也返回：调用方拿它生成冲突副本。
		// 丢掉的话，用户只看到一句「合不了」，两边改了什么得自己去找。
		return merged, false, nil
	}
	return merged, true, nil
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
	selected, err := PickVersion(versions, "", false, lockfileEntry(SyncTarget{SkillID: skillID}), false)
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

func ToHashMap(files []skillscan.FileDigest) map[string]string {
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
	if err := os.MkdirAll(filepath.Dir(to), 0o755); err != nil {
		return err
	}
	return os.WriteFile(to, data, 0o644)
}
