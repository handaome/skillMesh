package cmd

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/packager"
	"github.com/handaome/skillmesh/apps/cli/internal/syncstate"
)

// writeSkill 写一个最小的 Skill 目录，返回它的路径。
func writeSkill(t *testing.T, dir, content string) string {
	t.Helper()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "SKILL.md"), []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	return dir
}

// 基线只在双方认可同一份内容时才能前进。
// 一次「无变化」的同步就把逐文件摘要清掉的话，下一次真冲突就再也合不了。
func Test无变化的同步也留下逐文件基线(t *testing.T) {
	skill := writeSkill(t, filepath.Join(t.TempDir(), "demo"), "# demo\n")

	old := cfg.ConfigDir
	cfg.ConfigDir = t.TempDir()
	defer func() { cfg.ConfigDir = old }()

	artifact, err := packager.Pack(skill)
	if err != nil {
		t.Fatal(err)
	}
	target := syncTarget{Name: "demo", Agent: "claude-code", Path: skill}
	result, entry, err := applyDecision(context.Background(), nil, target, syncstate.UpToDate,
		artifact, manifestDigest(artifact.Manifest), apiclient.Skill{ID: "skill-1"}, true,
		syncOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if result.Outcome != skipped {
		t.Fatalf("应判无变化，得到 %s", result.Outcome)
	}
	if len(entry.BaseFiles) == 0 {
		t.Fatalf("无变化的同步不该把逐文件基线清掉：%+v", entry)
	}
	if entry.BaseSnapshot == "" {
		t.Fatalf("基线该留一份内容副本：%+v", entry)
	}
}

// 本地领先云端、这次只下载时，基线不能被写成本地当前内容：
// 写歪了下一轮就会判成「本地落后」，把用户本地的改动下载掉。
func Test方向过滤的同步不改基线(t *testing.T) {
	skill := writeSkill(t, filepath.Join(t.TempDir(), "demo"), "# demo\n")

	artifact, err := packager.Pack(skill)
	if err != nil {
		t.Fatal(err)
	}
	target := syncTarget{Name: "demo", Agent: "claude-code", Path: skill,
		BaseHash: "cloud-base", SkillID: "skill-1",
		BaseFiles: map[string]string{"SKILL.md": "sha"}, BaseSnapshot: "/base/snapshot"}
	_, entry, err := applyDecision(context.Background(), nil, target, syncstate.Upload,
		artifact, manifestDigest(artifact.Manifest), apiclient.Skill{ID: "skill-1"}, true,
		syncOptions{Direction: "download"})
	if err != nil {
		t.Fatal(err)
	}
	if entry.BaseHash != "cloud-base" {
		t.Fatalf("跳过上传不该动基线摘要，得到 %s", entry.BaseHash)
	}
	if entry.BaseSnapshot != "/base/snapshot" {
		t.Fatalf("跳过上传不该动基线副本，得到 %s", entry.BaseSnapshot)
	}
	if len(entry.BaseFiles) == 0 {
		t.Fatalf("跳过上传不该把逐文件基线清掉：%+v", entry)
	}
	// 下一轮不能判成「本地落后」——那会把用户本地的改动下载掉。
	next := syncstate.Decide(entry.BaseHash, manifestDigest(artifact.Manifest), "cloud-base")
	if next != syncstate.Upload {
		t.Fatalf("本地领先时不该判成下载，得到 %s", next)
	}
}

// 服务端明确判冲突才算冲突；服务端自己出错是失败——失败才进重试队列，
// 报成冲突会让用户去 --resolve，白折腾一轮。
func Test服务端出错不当成冲突(t *testing.T) {
	target := syncTarget{Name: "demo", Path: filepath.Join(t.TempDir(), "demo")}
	conflict := outcomeForError(target, fmt.Errorf("%w（云端已变）", errConflict))
	if conflict.Outcome != conflicted {
		t.Fatalf("服务端判的冲突该报冲突，得到 %s", conflict.Outcome)
	}
	failure := outcomeForError(target, errors.New("INTERNAL (500): 服务端崩了"))
	if failure.Outcome != failed {
		t.Fatalf("服务端出错该报失败，得到 %s", failure.Outcome)
	}
}
