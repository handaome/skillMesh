package cmd

import "testing"

// 全局参数（--agent / --scope / --dry-run）挂在根命令上，
// 子命令照样能用，而且「用户给没给」判断得了——
// install 靠它决定要不要沿用锁文件里上次装的那次。
func Test全局参数对子命令生效(t *testing.T) {
	root := newRootCmd()
	install, _, err := root.Find([]string{"install"})
	if err != nil {
		t.Fatal(err)
	}
	if err := install.ParseFlags([]string{"--agent", "hermes", "--scope", "project", "--dry-run"}); err != nil {
		t.Fatal(err)
	}
	if !install.Flags().Changed("agent") || !install.Flags().Changed("scope") {
		t.Fatal("子命令该看得到全局参数给没给")
	}
	if got := agentNamesOr("claude-code"); len(got) != 1 || got[0] != "hermes" {
		t.Fatalf("agent 没生效: %v", got)
	}
	if got := scopeOr("user"); got != "project" {
		t.Fatalf("scope 没生效: %s", got)
	}
	if !globalDryRun {
		t.Fatal("dry-run 没生效")
	}
}

// 没给全局参数时，各命令用自己的默认值：
// install 装到 claude-code 的 user 范围，scan/sync 用全部启用的 Agent。
func Test全局参数缺省时用命令自己的默认(t *testing.T) {
	root := newRootCmd()
	install, _, err := root.Find([]string{"install"})
	if err != nil {
		t.Fatal(err)
	}
	if err := install.ParseFlags(nil); err != nil {
		t.Fatal(err)
	}
	if install.Flags().Changed("agent") {
		t.Fatal("没给 --agent 不该算给过")
	}
	if got := agentNamesOr("claude-code"); len(got) != 1 || got[0] != "claude-code" {
		t.Fatalf("默认 agent 不对: %v", got)
	}
	if got := scopeOr("user"); got != "user" {
		t.Fatalf("默认 scope 不对: %s", got)
	}
	if globalDryRun {
		t.Fatal("默认不该是演练")
	}
	// scan 的默认是全部 Agent：不给 --agent 就没有名单。
	if got := agentNamesOr(); len(got) != 0 {
		t.Fatalf("scan 的默认该是空名单: %v", got)
	}
}

// 支持 --dry-run 的命令不该被提醒「不支持」。
func Test支持演练的命令不提醒(t *testing.T) {
	for _, name := range []string{"install", "add", "sync", "publish", "share", "update", "fix-skill"} {
		root := newRootCmd()
		sub, _, err := root.Find([]string{name})
		if err != nil {
			t.Fatal(err)
		}
		if sub.Annotations[dryRunAnnotation] != "true" {
			t.Fatalf("%s 该认 --dry-run", name)
		}
	}
}
