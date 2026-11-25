package cmd

import (
	"context"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
)

// 固定到 tag 的来源不自动漂移：版本只由用户明确升级才变
// （设计文档 §3.3.5「固定 commit/tag 的来源不自动漂移」）。
//
// 修之前这里有两个坑叠在一起：ls-remote 只给 tag 对象的 SHA，
// 跟本地记的 commit 永远不相等——注解 tag 的来源每次都报「有新版」。
func Test固定到标签的来源不跟着漂(t *testing.T) {
	previous := LsRemote
	defer func() { LsRemote = previous }()
	LsRemote = func(ctx context.Context, repo, ref string) (string, string, error) {
		return "1111111111111111111111111111111111111111", "tag", nil
	}

	lock := lockfile.Lock{Skills: []lockfile.LockedSkill{{
		Name: "demo", Repository: "https://example.test/demo", Ref: "v1.0.0",
		RefType: "tag", ResolvedCommit: "2222222222222222222222222222222222222222",
	}}}

	updates, err := checkUpdates(context.Background(), lock, false)
	if err != nil {
		t.Fatalf("检查更新失败: %v", err)
	}
	if len(updates) != 0 {
		t.Fatalf("固定到 tag 的来源不该报更新，实际报了 %d 条", len(updates))
	}
}

// 早期只按名字形状猜 ref 类型，标签被记成了 branch。
// 远端现在把这个 ref 解析成 tag，就该按固定来源对待，不能跟着它漂。
func Test锁文件里记成分支的标签也不跟着漂(t *testing.T) {
	previous := LsRemote
	defer func() { LsRemote = previous }()
	LsRemote = func(ctx context.Context, repo, ref string) (string, string, error) {
		return "1111111111111111111111111111111111111111", "tag", nil
	}

	lock := lockfile.Lock{Skills: []lockfile.LockedSkill{{
		Name: "demo", Repository: "https://example.test/demo", Ref: "v1.0.0",
		RefType: "branch", ResolvedCommit: "2222222222222222222222222222222222222222",
	}}}

	updates, err := checkUpdates(context.Background(), lock, false)
	if err != nil {
		t.Fatalf("检查更新失败: %v", err)
	}
	if len(updates) != 0 {
		t.Fatalf("远端解析成标签的来源不该报更新，实际报了 %d 条", len(updates))
	}
}
