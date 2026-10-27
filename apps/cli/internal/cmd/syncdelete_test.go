package cmd

import (
	"context"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/syncstate"
)

// cloudDeleteOpts 造一份带删除清单的同步选项。
func cloudDeleteOpts(slug, id string, dryRun bool) SyncOptions {
	return SyncOptions{
		DryRun: dryRun,
		CloudDeletionsKnown: true,
		CloudDeletes: map[string]apiclient.DeletedSkill{
			slug: {ID: id, Slug: slug, Name: slug},
		},
	}
}

// 云端删掉、本地基线里就是它：本地那份要跟着删。
func TestApplyCloudDeletesRemovesLocalCopy(t *testing.T) {
	state := syncstate.State{Skills: []syncstate.Entry{
		{Name: "yuanbao", Agent: "claude-code", SkillID: "id-1",
			Path: `C:\Users\u\.claude\skills\yuanbao`},
	}}
	targets := []SyncTarget{
		{Name: "yuanbao", Agent: "claude-code", Path: `C:\Users\u\.claude\skills\yuanbao`},
	}
	results, kept := applyCloudDeletes(context.Background(), &state, targets,
		cloudDeleteOpts("yuanbao", "id-1", true))
	if len(kept) != 0 {
		t.Fatalf("留在待同步里的 %d 条，want 0", len(kept))
	}
	if len(results) != 1 || results[0].Outcome != WouldDelete {
		t.Fatalf("结果 %v，want 一条将删除", results)
	}
}

// 云端删过，但本地基线记的不是它（另一台机器的副本）：本地留着，
// 但绝不能上传——上传就是复活。
func TestApplyCloudDeletesBlocksUploadOfForeignCopy(t *testing.T) {
	state := syncstate.State{Skills: []syncstate.Entry{
		{Name: "yuanbao", Agent: "claude-code", SkillID: "other-id",
			Path: `C:\Users\u\.claude\skills\yuanbao`},
	}}
	targets := []SyncTarget{
		{Name: "yuanbao", Agent: "claude-code", Path: `C:\Users\u\.claude\skills\yuanbao`},
	}
	results, kept := applyCloudDeletes(context.Background(), &state, targets,
		cloudDeleteOpts("yuanbao", "id-1", true))
	if len(kept) != 0 {
		t.Fatalf("留在待同步里的 %d 条，want 0（不能上传）", len(kept))
	}
	if len(results) != 1 || results[0].Outcome != Skipped {
		t.Fatalf("结果 %v，want 一条跳过", results)
	}
}

// 基线里压根没有这条（刚复制进来的目录）：同样不上传，但本地不动。
func TestApplyCloudDeletesBlocksUploadOfUnknownCopy(t *testing.T) {
	state := syncstate.State{}
	targets := []SyncTarget{
		{Name: "yuanbao", Agent: "claude-code", Path: `C:\Users\u\.claude\skills\yuanbao`},
	}
	results, kept := applyCloudDeletes(context.Background(), &state, targets,
		cloudDeleteOpts("yuanbao", "id-1", true))
	if len(kept) != 0 {
		t.Fatalf("留在待同步里的 %d 条，want 0（不能上传）", len(kept))
	}
	if len(results) != 1 || results[0].Outcome != Skipped {
		t.Fatalf("结果 %v，want 一条跳过", results)
	}
}

// 删除清单里没有的 Skill 不受影响。
func TestApplyCloudDeletesKeepsUnrelated(t *testing.T) {
	state := syncstate.State{}
	targets := []SyncTarget{
		{Name: "slides", Agent: "claude-code", Path: `C:\Users\u\.claude\skills\slides`},
	}
	results, kept := applyCloudDeletes(context.Background(), &state, targets,
		cloudDeleteOpts("yuanbao", "id-1", true))
	if len(kept) != 1 {
		t.Fatalf("留在待同步里的 %d 条，want 1", len(kept))
	}
	if len(results) != 0 {
		t.Fatalf("结果 %v，want 空", results)
	}
}
