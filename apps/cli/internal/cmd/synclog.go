package cmd

import (
	"context"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/oplog"
)

// recordSyncResult 记一次同步结果：本地日志 + 服务端审计。
//
// 上传、拉取、本地随云端删除——这三件事的结果只有客户端看得见。
// 服务端那边有版本记录（发过哪几版），但「云端删了之后本地那份被移进
// 回收站」它压根不知道，只能由客户端报上去。
//
// 记日志失败、报审计失败都忽略：这些是辅助动作，出了问题也不该
// 把同步本身的结果改掉。
func recordSyncResult(ctx context.Context, client *apiclient.Client,
	target SyncTarget, result syncResult) {

	action := syncLogAction(result.Outcome)
	if action == "" {
		return
	}
	if result.Outcome == Failed {
		opLog().RecordFailure(action, result.Name, result.Detail)
	} else {
		opLog().Record(action, result.Name, result.Detail, map[string]string{
			"agent": target.Agent,
			"path":  result.Path,
		})
	}
	// 服务端只收成功的那三类：失败与冲突留在本机日志里。
	switch result.Outcome {
	case Uploaded, fetched, Deleted:
	default:
		return
	}
	skillID := result.SkillID
	if skillID == "" {
		return
	}
	_ = client.ReportAuditEvent(ctx, action, skillID, "success",
		map[string]string{"name": result.Name, "detail": result.Detail})
}

// syncLogAction 把同步结果映射成日志动作名。
//
// 「无变化」不记：每次同步都刷一遍没有变化，日志会被噪声淹掉。
func syncLogAction(outcome syncOutcome) string {
	switch outcome {
	case Uploaded:
		return oplog.ActionSyncUpload
	case fetched:
		return oplog.ActionSyncDownload
	case Deleted:
		return oplog.ActionSyncDeleted
	case Conflicted:
		return oplog.ActionSyncConflict
	case Failed:
		return oplog.ActionSyncFailed
	default:
		return ""
	}
}
