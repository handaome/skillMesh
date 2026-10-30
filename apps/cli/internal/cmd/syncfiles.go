package cmd

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/packager"
	"github.com/handaome/skillmesh/apps/cli/internal/syncstate"
)

// 逐文件增量上传（设计文档 §6.2「未变化文件不重复传输」、详细设计 §2.4.2）。
//
// 同步上传以前走的是整包发布：把整个目录打成 zip 传上去。
// 改一行说明也要重传整个包——Skill 目录里常有大文件（参考资料、示例工程），
// 改三行字就重传几十兆。
//
// 这里走变更集协议：先算出哪些文件变了，只把这些传上去，
// 服务端拿基线补齐没变的那些（服务端那边见 SyncUploadService.mergeFiles）。

// changedFiles 找出这次要传的文件：内容与云端不一样的（新增或改动）。
//
// 云端没有这个文件就是新增；摘要不一样就是改动。一样的不用传。
func changedFiles(local packager.Artifact, cloud map[string]string) []packager.Entry {
	var changed []packager.Entry
	for _, item := range local.Manifest {
		if prev, ok := cloud[item.Path]; ok && prev == item.SHA256 {
			continue
		}
		changed = append(changed, item)
	}
	return changed
}

// deletedFiles 找出云端有、本地已经删掉的文件。
//
// 这些文件不会出现在这次上传里，服务端合成内容时会从基线里去掉它们。
func deletedFiles(local packager.Artifact, cloud map[string]string) []string {
	present := make(map[string]bool, len(local.Manifest))
	for _, item := range local.Manifest {
		present[item.Path] = true
	}
	var deleted []string
	for path := range cloud {
		if !present[path] {
			deleted = append(deleted, path)
		}
	}
	sort.Strings(deleted)
	return deleted
}

// uploadChangedFiles 把变了的文件逐个传到变更集里，然后提交。
//
// 传的是文件内容本身，不是整包：没变的文件服务端从基线里取。
// 返回传上去的文件数。
func uploadChangedFiles(ctx context.Context, client *apiclient.Client, changesetID string,
	local packager.Artifact, changed []packager.Entry, root string) (int, error) {

	if len(changed) == 0 {
		return 0, nil
	}
	specs := make([]apiclient.SyncFileSpec, 0, len(changed))
	byPath := make(map[string]packager.Entry, len(changed))
	for _, item := range changed {
		specs = append(specs, apiclient.SyncFileSpec{
			Path: item.Path, SHA256: item.SHA256, SizeBytes: item.SizeBytes,
			ContentType: contentTypeOf(item.Path),
		})
		byPath[item.Path] = item
	}
	slots, err := client.OpenChangesetUploads(ctx, changesetID, specs)
	if err != nil {
		return 0, fmt.Errorf("开上传会话失败: %w", err)
	}
	uploaded := 0
	for _, slot := range slots {
		// 读本地文件用的是自己清单里的路径，不是服务端回显的那个：
		// 服务端说什么就读什么，等于让它决定本机读哪个文件。
		item, ok := byPath[slot.Path]
		if !ok {
			return uploaded, fmt.Errorf("服务端要传的文件不在这次的清单里：%s", slot.Path)
		}
		data, err := os.ReadFile(filepath.Join(root, filepath.FromSlash(item.Path)))
		if err != nil {
			return uploaded, fmt.Errorf("读 %s 失败: %w", item.Path, err)
		}
		err = client.PutArtifact(ctx, apiclient.UploadTarget{
			Method: http.MethodPut, URL: slot.UploadURL, Headers: slot.Headers,
		}, data)
		if err != nil {
			return uploaded, err
		}
		// complete 让服务端校验字节数与摘要：通过才算这个文件传完了。
		if _, err := client.CompleteUpload(ctx, slot.UploadID); err != nil {
			return uploaded, err
		}
		uploaded++
	}
	return uploaded, nil
}

// uploadByChangeset 走变更集协议上传：只传变了的文件，服务端拿基线补齐其余的。
//
// 拿不到变更集、或云端没有已发布版本时不该走这里——退回整包发布那条路
// （sync.go 里的 PublishOne）。
func uploadByChangeset(ctx context.Context, client *apiclient.Client, target SyncTarget,
	local packager.Artifact, localHash string, skill apiclient.Skill, found bool) (syncResult, syncstate.Entry, error) {

	changed := changedFiles(local, target.CloudFiles)
	deleted := deletedFiles(local, target.CloudFiles)
	if len(changed) == 0 && len(deleted) == 0 {
		return failedResult(target, "没有要传的文件"), syncstate.Entry{}, nil
	}
	files, err := uploadChangedFiles(ctx, client, target.ChangesetID, local, changed, target.Path)
	if err != nil {
		return conflictOrFailure(target, err), syncstate.Entry{}, nil
	}
	committed, err := client.CommitChangeset(ctx, target.ChangesetID, baselineOf(target.CloudHash))
	if err != nil {
		return conflictOrFailure(target, err), syncstate.Entry{}, nil
	}
	version, _ := committed["version"].(string)
	status, _ := committed["version_status"].(string)
	if status != "published" && status != "pending_review" {
		return failedResult(target, fmt.Sprintf(
			"服务端没有返回可用的版本状态（拿到 %q），无法确认这次上传的结果", status)), syncstate.Entry{}, nil
	}

	entry := syncstate.Entry{
		Name: target.Name, Agent: target.Agent,
		BaseHash: target.BaseHash, SkillID: target.SkillID,
		BaseFiles: target.BaseFiles, BaseSnapshot: target.BaseSnapshot,
		Path: target.Path,
	}
	entry.SkillID = skill.ID
	entry.LastVersion = version
	// 版本还没转正（待审）时基线不能往前挪：云端已发布的那份还是旧的，
	// 基线一挪，下一轮就会判成「云端有更新」把本地改动下载掉。
	if status == "published" {
		entry.BaseHash = localHash
		saveBaseline(target, &entry)
	}
	detail := fmt.Sprintf("%s（传了 %d 个文件）", version, files)
	if !found {
		detail = "云端新建 " + detail
	}
	return syncResult{Name: target.Name, Path: target.Path,
		Outcome: Uploaded, Detail: detail}, entry, nil
}

// conflictOrFailure 把上传时的错误归成同步结果。
//
// 服务端说冲突（409）是要人来定夺，不是重试队列该收的；别的错才算失败。
func conflictOrFailure(target SyncTarget, err error) syncResult {
	if apiclient.CodeOf(err) == "VERSION_CONFLICT" {
		return OutcomeForError(target, fmt.Errorf("%w：%s", ErrConflict, err.Error()))
	}
	return failedResult(target, err.Error())
}

// contentTypeOf 猜文件的内容类型。
//
// 只是给服务端一个提示（存对象时用得上），猜错不影响正确性——
// 服务端校验的是字节数与摘要，不是这个头。
func contentTypeOf(path string) string {
	switch strings.ToLower(filepath.Ext(path)) {
	case ".md":
		return "text/markdown"
	case ".json":
		return "application/json"
	case ".yaml", ".yml":
		return "application/yaml"
	case ".txt":
		return "text/plain"
	case ".py":
		return "text/x-python"
	case ".js", ".ts":
		return "text/javascript"
	case ".sh":
		return "application/x-sh"
	default:
		return "application/octet-stream"
	}
}
