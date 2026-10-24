package cmd

import "testing"

// 巡检的状态判定：本机磁盘是硬事实，云端是另一边的事实，两边合起来才是真状态。
func Test巡检判定状态(t *testing.T) {
	cases := []struct {
		name   string
		facts  installFacts
		status string
	}{
		{
			name:   "目录没了是已卸载",
			facts:  installFacts{Exists: false},
			status: "uninstalled",
		},
		{
			name:   "目录在但SKILL.md没了是缺失",
			facts:  installFacts{Exists: true, HasSkill: false},
			status: "missing",
		},
		{
			name: "一切正常是活跃",
			facts: installFacts{
				Exists: true, HasSkill: true, Digest: "aaa",
				RecordedFound: true, RecordedVersion: "1.0.0", RecordedStatus: "published",
				Baseline: "aaa", LatestVersion: "1.0.0", LatestDigest: "aaa",
			},
			status: "active",
		},
		{
			name: "服务端记的那一版云端找不到了是已封禁",
			facts: installFacts{
				Exists: true, HasSkill: true, Digest: "aaa",
				RecordedFound: false,
			},
			status: "blocked",
		},
		{
			name: "本机改过且云端也动了是冲突",
			facts: installFacts{
				Exists: true, HasSkill: true, Digest: "bbb",
				RecordedFound: true, RecordedVersion: "1.0.0", RecordedStatus: "published",
				Baseline: "aaa", LatestVersion: "1.0.1", LatestDigest: "ccc",
			},
			status: "conflict",
		},
		{
			name: "本机改过但云端没动只是本地改过",
			facts: installFacts{
				Exists: true, HasSkill: true, Digest: "bbb",
				RecordedFound: true, RecordedVersion: "1.0.0", RecordedStatus: "published",
				Baseline: "aaa", LatestVersion: "1.0.0", LatestDigest: "aaa",
			},
			status: "active",
		},
		{
			name: "记的那一版已被撤回是已封禁",
			facts: installFacts{
				Exists: true, HasSkill: true, Digest: "aaa",
				RecordedFound: true, RecordedVersion: "1.0.0", RecordedStatus: "revoked",
				Baseline: "aaa", LatestVersion: "1.0.1", LatestDigest: "aaa",
			},
			status: "blocked",
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			out := judgeInstall(c.facts)
			if out.Status != c.status {
				t.Fatalf("状态判定不对：期望 %s，得到 %s（说明 %s）", c.status, out.Status, out.Note)
			}
		})
	}
}

// 本机内容与服务端记的那一版一致时，不算「本地改过」。
func Test巡检本地改过标记(t *testing.T) {
	same := judgeInstall(installFacts{
		Exists: true, HasSkill: true, Digest: "aaa",
		RecordedFound: true, RecordedVersion: "1.0.0", RecordedStatus: "published",
		Baseline: "aaa", LatestVersion: "1.0.0", LatestDigest: "aaa",
	})
	if same.LocalModified == nil || *same.LocalModified {
		t.Fatalf("内容与服务端一致时不该算本地改过，得到 %v", same.LocalModified)
	}

	changed := judgeInstall(installFacts{
		Exists: true, HasSkill: true, Digest: "bbb",
		RecordedFound: true, RecordedVersion: "1.0.0", RecordedStatus: "published",
		Baseline: "aaa", LatestVersion: "1.0.0", LatestDigest: "aaa",
	})
	if changed.LocalModified == nil || !*changed.LocalModified {
		t.Fatalf("内容与服务端不一致时该标记本地改过，得到 %v", changed.LocalModified)
	}
}
