package syncstate

import "testing"

// 设计文档 §6.3 的冲突策略：双方都变更时必须进入冲突，不能自动覆盖。
func Test三方比较定出同步动作(t *testing.T) {
	cases := []struct {
		name               string
		base, local, cloud string
		want               Decision
	}{
		{"两边一样", "a", "b", "b", UpToDate},
		{"没有基线以本地为准", "", "b", "c", Upload},
		{"只有本地变了", "a", "b", "a", Upload},
		{"只有云端变了", "a", "a", "b", Download},
		{"两边都变了", "a", "b", "c", Conflict},
	}
	for _, c := range cases {
		if got := Decide(c.base, c.local, c.cloud); got != c.want {
			t.Errorf("%s: 期望 %s，得到 %s", c.name, c.want, got)
		}
	}
}
