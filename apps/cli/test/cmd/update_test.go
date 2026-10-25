package cmd_test

import (
	"context"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	cmd "github.com/handaome/skillmesh/apps/cli/internal/cmd"
	"github.com/handaome/skillmesh/apps/cli/internal/config"
	"github.com/handaome/skillmesh/apps/cli/internal/installer"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/trash"
)

func makeRepo(t *testing.T, content string) (path, commit string) {
	t.Helper()
	dir := t.TempDir()
	runGit(t, dir, "init", "-q")
	write(t, filepath.Join(dir, "SKILL.md"), content)
	runGit(t, dir, "add", "-A")
	runGit(t, dir, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init")
	return dir, runGit(t, dir, "rev-parse", "HEAD")
}

func write(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func runGit(t *testing.T, dir string, args ...string) string {
	t.Helper()
	command := exec.Command("git", args...)
	command.Dir = dir
	out, err := command.CombinedOutput()
	if err != nil {
		t.Fatalf("git %v 失败: %v\n%s", args, err, out)
	}
	return strings.TrimSpace(string(out))
}

// updateSetup 造一个能跑更新的现场：Agent 目录与配置目录都在临时目录里。
func updateSetup(t *testing.T) (skillsDir, configDir string) {
	t.Helper()
	skillsDir = filepath.Join(t.TempDir(), "skills")
	configDir = t.TempDir()
	original := cmd.Cfg
	cmd.Cfg = config.Config{
		ConfigDir: configDir,
		Agents: map[string]config.Agent{
			"claude-code": {Enabled: true, UserSkillsDir: skillsDir},
		},
	}
	t.Cleanup(func() { cmd.Cfg = original })
	return skillsDir, configDir
}

// makeSkillRepo 造一个仓库：demo/SKILL.md 从 v1 变成 v2。
func makeSkillRepo(t *testing.T) (repo, first, second string) {
	t.Helper()
	repo = t.TempDir()
	runGit(t, repo, "init", "-q")
	skill := filepath.Join(repo, "demo")
	if err := os.MkdirAll(skill, 0o755); err != nil {
		t.Fatal(err)
	}
	write(t, filepath.Join(skill, "SKILL.md"), "# v1\n")
	runGit(t, repo, "add", "-A")
	runGit(t, repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "v1")
	first = runGit(t, repo, "rev-parse", "HEAD")
	write(t, filepath.Join(skill, "SKILL.md"), "# v2\n")
	runGit(t, repo, "add", "-A")
	runGit(t, repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "v2")
	second = runGit(t, repo, "rev-parse", "HEAD")
	return repo, first, second
}

// 用户自己写的同名 Skill 不该被 update 静默覆盖掉。
func Test更新拒绝覆盖不受管的同名目录(t *testing.T) {
	skillsDir, _ := updateSetup(t)
	target := filepath.Join(skillsDir, "demo")
	if err := os.MkdirAll(target, 0o755); err != nil {
		t.Fatal(err)
	}
	write(t, filepath.Join(target, "SKILL.md"), "# 我自己写的\n")

	repo, first, _ := makeSkillRepo(t)
	lock := lockfile.Lock{Skills: []lockfile.LockedSkill{{
		Name: "demo", Repository: repo, Path: "demo",
		ResolvedCommit: first, Scope: "user", Agents: []string{"claude-code"},
	}}}
	updates, err := cmd.CheckUpdates(context.Background(), lock)
	if err != nil || len(updates) != 1 {
		t.Fatalf("应报出 1 条更新：%v", err)
	}
	if err := cmd.ApplyOne(context.Background(), repo, t.TempDir(), &lock, updates[0], "commit-new"); err == nil {
		t.Fatal("覆盖不受管理的同名目录时应该失败")
	}
	data, err := os.ReadFile(filepath.Join(target, "SKILL.md"))
	if err != nil || string(data) != "# 我自己写的\n" {
		t.Fatalf("用户自己写的同名 Skill 不该被动：%q（%v）", string(data), err)
	}
}

// 上游新推的版本里带真凭据时，自动更新必须拒绝装进去
// （设计文档 §13「安全扫描命中 → 阻止自动更新」）。
func Test更新拒绝含凭据的上游版本(t *testing.T) {
	skillsDir, _ := updateSetup(t)
	target := filepath.Join(skillsDir, "demo")
	if err := os.MkdirAll(target, 0o755); err != nil {
		t.Fatal(err)
	}
	write(t, filepath.Join(target, "SKILL.md"), "# v1\n")
	if err := installer.MarkInstalled(target, "https://github.com/x/y"); err != nil {
		t.Fatal(err)
	}

	// 仓库里有一份带 AWS key 的新版本。
	repo := t.TempDir()
	runGit(t, repo, "init", "-q")
	skill := filepath.Join(repo, "demo")
	if err := os.MkdirAll(skill, 0o755); err != nil {
		t.Fatal(err)
	}
	write(t, filepath.Join(skill, "SKILL.md"), "# v1\n")
	runGit(t, repo, "add", "-A")
	runGit(t, repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "v1")
	first := runGit(t, repo, "rev-parse", "HEAD")
	write(t, filepath.Join(skill, "secret.sh"), "AWS_KEY=AKIAIOSFODNN7EXAMPLE\n")
	runGit(t, repo, "add", "-A")
	runGit(t, repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "v2")

	lock := lockfile.Lock{Skills: []lockfile.LockedSkill{{
		Name: "demo", Repository: repo, Path: "demo",
		ResolvedCommit: first, Scope: "user", Agents: []string{"claude-code"},
	}}}
	updates, err := cmd.CheckUpdates(context.Background(), lock)
	if err != nil || len(updates) != 1 {
		t.Fatalf("应报出 1 条更新：%v", err)
	}
	err = cmd.ApplyOne(context.Background(), repo, t.TempDir(), &lock, updates[0], "commit-new")
	if err == nil {
		t.Fatal("含凭据的上游版本不该被装进去")
	}
	if !strings.Contains(err.Error(), "凭据") {
		t.Fatalf("错误信息应说明是凭据问题：%v", err)
	}
	// 本机那份干净的 v1 不能被动。
	data, err := os.ReadFile(filepath.Join(target, "SKILL.md"))
	if err != nil || string(data) != "# v1\n" {
		t.Fatalf("本机版本不该被换掉：%q（%v）", string(data), err)
	}
	if _, err := os.Stat(filepath.Join(target, "secret.sh")); err == nil {
		t.Fatal("带凭据的文件不该被装进来")
	}
}

// 旧版本先备份进回收站（装砸了能找回），锁文件里的策略与来源类型也不能被洗掉。
func Test更新前旧版本备份进回收站(t *testing.T) {
	skillsDir, configDir := updateSetup(t)
	target := filepath.Join(skillsDir, "demo")
	if err := os.MkdirAll(target, 0o755); err != nil {
		t.Fatal(err)
	}
	write(t, filepath.Join(target, "SKILL.md"), "# v1\n")
	if err := installer.MarkInstalled(target, "https://github.com/x/y"); err != nil {
		t.Fatal(err)
	}

	repo, first, second := makeSkillRepo(t)
	lock := lockfile.Lock{Skills: []lockfile.LockedSkill{{
		Name: "demo", Repository: repo, Path: "demo",
		ResolvedCommit: first, Scope: "user", Agents: []string{"claude-code"},
		UpdatePolicy: "auto_safe", Provider: "github",
	}}}
	updates, err := cmd.CheckUpdates(context.Background(), lock)
	if err != nil || len(updates) != 1 {
		t.Fatalf("应报出 1 条更新：%v", err)
	}
	if err := cmd.ApplyOne(context.Background(), repo, t.TempDir(), &lock, updates[0], second); err != nil {
		t.Fatal(err)
	}

	// 新版本装上去了
	data, err := os.ReadFile(filepath.Join(target, "SKILL.md"))
	if err != nil || string(data) != "# v2\n" {
		t.Fatalf("应装上 v2，得到 %q（%v）", string(data), err)
	}
	// 旧版本在回收站里
	items, err := trash.NewStore(configDir).Items()
	if err != nil || len(items) != 1 {
		t.Fatalf("回收站里应有 1 条备份，得到 %d 条（%v）", len(items), err)
	}
	old, err := os.ReadFile(filepath.Join(configDir, "trash", items[0].Entry, "SKILL.md"))
	if err != nil || string(old) != "# v1\n" {
		t.Fatalf("回收站里应是旧版本 v1，得到 %q（%v）", string(old), err)
	}
	// 锁文件里的更新策略与来源类型还在
	entry, ok := lock.Find("demo")
	if !ok {
		t.Fatalf("锁文件里没有 demo：%+v", lock.Skills)
	}
	if entry.UpdatePolicy != "auto_safe" || entry.Provider != "github" {
		t.Fatalf("更新后策略与来源类型丢了：%+v", entry)
	}
}

func Test无变更时不报告更新(t *testing.T) {
	repo, commit := makeRepo(t, "# v1\n")
	lock := lockfile.Lock{Skills: []lockfile.LockedSkill{{
		Name: "demo", Repository: repo, ResolvedCommit: commit,
	}}}

	updates, err := cmd.CheckUpdates(context.Background(), lock)
	if err != nil {
		t.Fatal(err)
	}
	if len(updates) != 0 {
		t.Fatalf("期望无更新，得到 %+v", updates)
	}
}

func Test上游有新提交时报告更新(t *testing.T) {
	repo, first := makeRepo(t, "# v1\n")
	write(t, filepath.Join(repo, "SKILL.md"), "# v2\n")
	runGit(t, repo, "add", "-A")
	runGit(t, repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "v2")
	second := runGit(t, repo, "rev-parse", "HEAD")

	lock := lockfile.Lock{Skills: []lockfile.LockedSkill{{
		Name: "demo", Repository: repo, ResolvedCommit: first,
	}}}
	updates, err := cmd.CheckUpdates(context.Background(), lock)
	if err != nil {
		t.Fatal(err)
	}
	if len(updates) != 1 {
		t.Fatalf("期望 1 条更新，得到 %+v", updates)
	}
	if updates[0].RemoteCommit != second {
		t.Fatalf("远端 commit 不对：得到 %s，期望 %s", updates[0].RemoteCommit, second)
	}
	if updates[0].LocalCommit != first || updates[0].Name != "demo" {
		t.Fatalf("更新信息不对: %+v", updates[0])
	}
}

func Test同一仓库只查一次远端(t *testing.T) {
	repo, first := makeRepo(t, "# v1\n")
	write(t, filepath.Join(repo, "SKILL.md"), "# v2\n")
	runGit(t, repo, "add", "-A")
	runGit(t, repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "v2")

	// 一个仓库里 --all 装了几十个 Skill，远端 commit 只有一个，
	// 不该每个 Skill 都发一次 ls-remote。
	var calls int
	original := cmd.LsRemote
	cmd.LsRemote = func(ctx context.Context, repository, ref string) (string, error) {
		calls++
		return original(ctx, repository, ref)
	}
	defer func() { cmd.LsRemote = original }()

	var skills []lockfile.LockedSkill
	for _, name := range []string{"a", "b", "c", "d", "e"} {
		skills = append(skills, lockfile.LockedSkill{
			Name: name, Repository: repo, ResolvedCommit: first,
		})
	}
	updates, err := cmd.CheckUpdates(context.Background(), lockfile.Lock{Skills: skills})
	if err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Fatalf("同一仓库应只查 1 次，实际查了 %d 次", calls)
	}
	if len(updates) != 5 {
		t.Fatalf("期望 5 条更新，得到 %d", len(updates))
	}
}

func Test不同仓库分别查(t *testing.T) {
	repoA, firstA := makeRepo(t, "# a\n")
	repoB, firstB := makeRepo(t, "# b\n")

	var calls int
	original := cmd.LsRemote
	cmd.LsRemote = func(ctx context.Context, repository, ref string) (string, error) {
		calls++
		return original(ctx, repository, ref)
	}
	defer func() { cmd.LsRemote = original }()

	lock := lockfile.Lock{Skills: []lockfile.LockedSkill{
		{Name: "a", Repository: repoA, ResolvedCommit: firstA},
		{Name: "b", Repository: repoB, ResolvedCommit: firstB},
	}}
	if _, err := cmd.CheckUpdates(context.Background(), lock); err != nil {
		t.Fatal(err)
	}
	if calls != 2 {
		t.Fatalf("两个仓库应查 2 次，实际 %d 次", calls)
	}
}

// 只更新一个 Skill 不能把锁文件里其余条目抹掉——
// 那是用户装在别的机器上的东西，抹了就再也找不回来。
func Test更新单个Skill不丢锁文件里的其他条目(t *testing.T) {
	skillsDir, _ := updateSetup(t)
	repo, first, second := makeSkillRepo(t)

	projectRoot := t.TempDir()
	t.Chdir(projectRoot)

	lock := lockfile.Lock{Skills: []lockfile.LockedSkill{
		{Name: "demo", Repository: repo, Path: "demo", RefType: "branch",
			ResolvedCommit: first, Scope: "user", Agents: []string{"claude-code"}},
		{Name: "other", Repository: "https://github.com/x/y", Path: "other",
			RefType: "branch", ResolvedCommit: "abc", Scope: "user",
			Agents: []string{"claude-code"}},
	}}
	if err := lock.Save(projectRoot); err != nil {
		t.Fatal(err)
	}

	command := cmd.NewUpdateCmd()
	command.SetArgs([]string{"demo"})
	command.SetOut(io.Discard)
	command.SetErr(io.Discard)
	if err := command.Execute(); err != nil {
		t.Fatal(err)
	}

	saved, err := lockfile.Load(projectRoot)
	if err != nil {
		t.Fatal(err)
	}
	if len(saved.Skills) != 2 {
		t.Fatalf("更新一个 Skill 不该动其余条目，锁文件里应还有 2 条，实际 %d 条：%+v",
			len(saved.Skills), saved.Skills)
	}
	entry, ok := saved.Find("other")
	if !ok {
		t.Fatal("other 被抹掉了")
	}
	if entry.ResolvedCommit != "abc" {
		t.Fatalf("other 的内容被改了：%+v", entry)
	}
	updated, ok := saved.Find("demo")
	if !ok {
		t.Fatal("demo 不在锁文件里")
	}
	if updated.ResolvedCommit != second {
		t.Fatalf("demo 该更新到 %s，实际 %s", second, updated.ResolvedCommit)
	}
	_ = skillsDir
}

// manual 策略的来源不该被后台探测：策略是「不自动检查或应用」
// （设计文档 §7.2）。notify 与 auto_safe 照查。
func Test后台检查跳过manual策略(t *testing.T) {
	repo, first := makeRepo(t, "# v1\n")
	write(t, filepath.Join(repo, "SKILL.md"), "# v2\n")
	runGit(t, repo, "add", "-A")
	runGit(t, repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "v2")

	lock := lockfile.Lock{Skills: []lockfile.LockedSkill{
		{Name: "manual-one", Repository: repo, ResolvedCommit: first, UpdatePolicy: "manual"},
		{Name: "notify-one", Repository: repo, ResolvedCommit: first, UpdatePolicy: "notify"},
		{Name: "auto-one", Repository: repo, ResolvedCommit: first, UpdatePolicy: "auto_safe"},
	}}

	var checked []string
	original := cmd.LsRemote
	cmd.LsRemote = func(ctx context.Context, repository, ref string) (string, error) {
		checked = append(checked, repository)
		return original(ctx, repository, ref)
	}
	defer func() { cmd.LsRemote = original }()

	updates, err := cmd.CheckUpdatesBackground(context.Background(), lock)
	if err != nil {
		t.Fatal(err)
	}
	for _, update := range updates {
		if update.Name == "manual-one" {
			t.Fatal("manual 策略不该被后台检查")
		}
	}
	if len(updates) != 2 {
		t.Fatalf("notify 与 auto_safe 都该被检查，实际 %d 条：%+v", len(updates), updates)
	}
}

// notify 与 auto_safe 要分得开：notify 只报，auto_safe 才自动应用
// （设计文档 §7.2）。
func Test按策略筛出可自动应用的更新(t *testing.T) {
	lock := lockfile.Lock{Skills: []lockfile.LockedSkill{
		{Name: "a", UpdatePolicy: "notify"},
		{Name: "b", UpdatePolicy: "auto_safe"},
		{Name: "c", UpdatePolicy: "manual"},
		{Name: "d"}, // 没登记策略，按 notify 算
	}}
	updates := []cmd.UpdateInfo{
		{Name: "a"}, {Name: "b"}, {Name: "c"}, {Name: "d"},
	}

	auto := cmd.FilterAutoSafe(lock, updates)
	if len(auto) != 1 || auto[0].Name != "b" {
		t.Fatalf("只有 auto_safe 该自动应用：%+v", auto)
	}
	notify := cmd.FilterByPolicy(lock, updates, "notify")
	if len(notify) != 2 {
		t.Fatalf("notify 加上没登记策略的应有两项：%+v", notify)
	}
	for _, u := range notify {
		if u.Name != "a" && u.Name != "d" {
			t.Fatalf("不该包含 %s：%+v", u.Name, notify)
		}
	}
}
