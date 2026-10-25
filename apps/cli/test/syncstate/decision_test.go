package syncstate_test

import (
	syncstate "github.com/handaome/skillmesh/apps/cli/internal/syncstate"
	"testing"
)

// 设计文档 §6.3 的冲突策略：双方都变更时必须进入冲突，不能自动覆盖。
func Test三方比较定出同步动作(t *testing.T) {
	cases := []struct {
		name               string
		base, local, cloud string
		want               syncstate.Decision
	}{
		{"两边一样", "a", "b", "b", syncstate.UpToDate},
		{"没有基线以本地为准", "", "b", "c", syncstate.Upload},
		{"只有本地变了", "a", "b", "a", syncstate.Upload},
		{"只有云端变了", "a", "a", "b", syncstate.Download},
		{"两边都变了", "a", "b", "c", syncstate.Conflict},
		// 云端的库被重建过：云端没有这个 Skill，本地也没变——
		// 该重新传上去，不是去下载一个不存在的 Skill。
		{"云端没有可比内容且本地没变", "a", "a", "", syncstate.Upload},
		{"云端没有可比内容且本地变了", "a", "b", "", syncstate.Upload},
	}
	for _, c := range cases {
		if got := syncstate.Decide(c.base, c.local, c.cloud); got != c.want {
			t.Errorf("%s: 期望 %s，得到 %s", c.name, c.want, got)
		}
	}
}
