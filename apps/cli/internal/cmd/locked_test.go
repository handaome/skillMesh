package cmd

import (
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
)

// v1 切片 4 的验收标准：`install --locked` 在锁文件对不上时必须失败，
// 否则 CI 会静默装到漂移的版本。
func Test锁文件对不上时装不上(t *testing.T) {
	versions := apiclient.VersionPage{Items: []apiclient.SkillVersion{{
		Version:  "1.2.0",
		Status:   "published",
		Artifact: &apiclient.ArtifactSummary{SHA256: "云端实际摘要"},
	}}}

	// 锁文件记的摘要和云端不一致 → 拒绝
	locked := lockfile.LockedSkill{Version: "1.2.0", SHA256: "锁文件里的旧摘要"}
	if _, err := pickVersion(versions, "", true, locked, true); err == nil {
		t.Fatal("锁文件摘要与云端不一致时应该失败")
	}

	// 对得上 → 放行，并且装的是锁文件指定的版本
	locked.SHA256 = "云端实际摘要"
	selected, err := pickVersion(versions, "", true, locked, true)
	if err != nil {
		t.Fatalf("锁文件一致时应放行：%v", err)
	}
	if selected.Version != "1.2.0" {
		t.Fatalf("应装锁文件指定的版本，得到 %s", selected.Version)
	}

	// 锁文件里的版本云端没有 → 失败，不能悄悄换成最新的
	locked.Version = "9.9.9"
	if _, err := pickVersion(versions, "", true, locked, true); err == nil {
		t.Fatal("锁文件指定的版本云端不存在时应该失败")
	}
}
