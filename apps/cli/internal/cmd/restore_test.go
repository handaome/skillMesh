package cmd

import "testing"

func TestDecideRestore(t *testing.T) {
	cases := []struct {
		name    string
		exists  bool
		local   string
		remote  string
		marker  string
		replace bool
		want    restoreAction
	}{
		{name: "本地没有就装", exists: false, want: restoreDoInstall},
		{name: "内容一致就不重装", exists: true, local: "abc", remote: "abc",
			marker: "skillmesh:s1", want: restoreDoSame},
		{name: "同一个 Skill 云端更新了就覆盖", exists: true, local: "abc", remote: "def",
			marker: "skillmesh:s1", want: restoreDoInstall},
		{name: "装的是别的 Skill 就不碰", exists: true, local: "abc", remote: "def",
			marker: "skillmesh:s2", want: restoreDoSkip},
		{name: "不受管的目录就不碰", exists: true, local: "abc", remote: "def",
			marker: "", want: restoreDoSkip},
		{name: "不受管的加 replace 才覆盖", exists: true, local: "abc", remote: "def",
			marker: "", replace: true, want: restoreDoInstall},
		{name: "本地摘要读不出来就不算一致", exists: true, local: "", remote: "def",
			marker: "skillmesh:s1", want: restoreDoInstall},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := decideRestore(c.exists, c.local, c.remote, c.marker, "skillmesh:s1", c.replace)
			if got != c.want {
				t.Fatalf("decideRestore = %v, want %v", got, c.want)
			}
		})
	}
}

func TestMergeRestoreState(t *testing.T) {
	if got := mergeRestoreState("", restoreDone); got != restoreDone {
		t.Fatalf("第一次合并 = %q, want %q", got, restoreDone)
	}
	if got := mergeRestoreState(restoreSame, restoreFailed); got != restoreFailed {
		t.Fatalf("合并 = %q, want %q", got, restoreFailed)
	}
	if got := mergeRestoreState(restoreFailed, restoreSame); got != restoreFailed {
		t.Fatalf("合并 = %q, want %q", got, restoreFailed)
	}
	if got := mergeRestoreState(restoreDone, restoreSkip); got != restoreDone {
		t.Fatalf("合并 = %q, want %q", got, restoreDone)
	}
}
