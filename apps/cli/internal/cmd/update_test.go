package cmd

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/config"
	"github.com/handaome/skillmesh/apps/cli/internal/installer"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/oplog"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
)

// 本地和远端改了同一个文件时，更新不能静默覆盖本机那份，
// 还得留下查得到的冲突记录（设计文档 §4.3 UPD-002）。
func Test更新遇到本地改动不覆盖并记下冲突(t *testing.T) {
	repo, first, second := skillRepoWithTwoVersions(t)

	skillsDir := t.TempDir()
	target := filepath.Join(skillsDir, "demo")
	if err := os.MkdirAll(target, 0o755); err != nil {
		t.Fatal(err)
	}
	writeSkillFile(t, target, demoSkillFile("v1"))
	// 登记成受管的：没有这一步，更新会因为「同名目录不受管」被拒绝，
	// 断言就测不到「本地改动有没有被覆盖」了。
	if err := installer.MarkInstalled(target, "https://example.test/demo"); err != nil {
		t.Fatal(err)
	}
	installed, err := skillscan.DirDigest(target)
	if err != nil {
		t.Fatal(err)
	}
	// 用户把本机这份改了：同一个文件，本地和远端都动过。
	writeSkillFile(t, target, demoSkillFile("我改过的"))

	previous := Cfg
	defer func() { Cfg = previous }()
	Cfg = config.Config{
		ConfigDir: t.TempDir(),
		CacheDir:  t.TempDir(),
		Agents: map[string]config.Agent{
			"claude-code": {Enabled: true, UserSkillsDir: skillsDir},
		},
	}

	update := UpdateInfo{
		Name: "demo", Repository: repo, Path: "demo", Scope: "user",
		InstalledHash: installed, Agents: []string{"claude-code"},
		LocalCommit: first, RemoteCommit: second,
		TargetPaths: map[string]string{"claude-code": target},
	}
	lock := lockfile.Lock{Skills: []lockfile.LockedSkill{{
		Name: "demo", Repository: repo, Path: "demo", ResolvedCommit: first,
		Scope: "user", Agents: []string{"claude-code"}, InstalledHash: installed,
		TargetPaths: map[string]string{"claude-code": target},
	}}}

	if err := applyUpdates(context.Background(), t.TempDir(), &lock,
		[]UpdateInfo{update}, false, false); err != nil {
		t.Fatalf("应用更新失败: %v", err)
	}

	// 本机那份不能被远端盖掉。
	data, err := os.ReadFile(filepath.Join(target, "SKILL.md"))
	if err != nil || !strings.Contains(string(data), "我改过的") {
		t.Fatalf("本机那份被远端覆盖了: %q（%v）", string(data), err)
	}
	// 冲突得有记录：终端上一行字滚过去就没了，事后要查得到。
	found := false
	for _, entry := range oplog.Tail(opLogPath(), 50, "") {
		if entry.Action == oplog.ActionUpdateConflict && entry.Skill == "demo" {
			found = true
		}
	}
	if !found {
		t.Fatalf("没有留下冲突记录，skillmesh logs 里查不到这次跳过")
	}
}

// 上游 commit 变了、本机没改动 → 更新直接生效，但要不要自动应用由更新策略说了算
// （设计文档 §4.3 UPD-001、§7.2）。
func Test本地没改动时按更新策略应用更新(t *testing.T) {
	repo, first, second := skillRepoWithTwoVersions(t)

	skillsDir := t.TempDir()
	target := filepath.Join(skillsDir, "demo")
	if err := os.MkdirAll(target, 0o755); err != nil {
		t.Fatal(err)
	}
	writeSkillFile(t, target, demoSkillFile("v1"))
	if err := installer.MarkInstalled(target, repo); err != nil {
		t.Fatal(err)
	}
	installed, err := skillscan.DirDigest(target)
	if err != nil {
		t.Fatal(err)
	}

	previous := Cfg
	defer func() { Cfg = previous }()
	Cfg = config.Config{
		ConfigDir: t.TempDir(),
		CacheDir:  t.TempDir(),
		Agents: map[string]config.Agent{
			"claude-code": {Enabled: true, UserSkillsDir: skillsDir},
		},
	}

	update := UpdateInfo{
		Name: "demo", Repository: repo, Path: "demo", Scope: "user",
		InstalledHash: installed, Agents: []string{"claude-code"},
		LocalCommit: first, RemoteCommit: second,
		UpdatePolicy: "auto_safe",
		TargetPaths:  map[string]string{"claude-code": target},
	}
	lock := lockfile.Lock{Skills: []lockfile.LockedSkill{{
		Name: "demo", Repository: repo, Path: "demo", ResolvedCommit: first,
		Scope: "user", Agents: []string{"claude-code"}, InstalledHash: installed,
		UpdatePolicy: "auto_safe",
		TargetPaths:  map[string]string{"claude-code": target},
	}}}

	// 策略说了算：auto_safe 的进自动更新那一档，manual 的不进。
	if got := FilterByPolicy(lock, []UpdateInfo{update}, "auto_safe"); len(got) != 1 {
		t.Fatalf("auto_safe 的来源该进自动更新，实际 %d 个", len(got))
	}
	if got := FilterByPolicy(lock, []UpdateInfo{update}, "manual"); len(got) != 0 {
		t.Fatalf("manual 的来源不该进自动更新，实际 %d 个", len(got))
	}

	if err := applyUpdates(context.Background(), t.TempDir(), &lock,
		[]UpdateInfo{update}, false, false); err != nil {
		t.Fatalf("应用更新失败: %v", err)
	}

	// 本机没改动 → 本机那份换成上游的 v2。
	data, err := os.ReadFile(filepath.Join(target, "SKILL.md"))
	if err != nil || !strings.Contains(string(data), "v2") {
		t.Fatalf("本机那份没更新到上游版本: %q（%v）", string(data), err)
	}
	// 锁文件里记的 commit 跟着走。
	if lock.Skills[0].ResolvedCommit != second {
		t.Fatalf("锁文件里的 commit 没跟着更新: %s", lock.Skills[0].ResolvedCommit)
	}
	// 策略不能被更新洗掉：更新一次就把「自动更新」丢了，等于让用户重新配一遍。
	if lock.Skills[0].UpdatePolicy != "auto_safe" {
		t.Fatalf("更新把更新策略洗掉了: %q", lock.Skills[0].UpdatePolicy)
	}
}

// demoSkillFile 造一份合规的 SKILL.md：正文里带个标记，方便看内容有没有被换掉。
func demoSkillFile(marker string) string {
	return "---\nname: demo\ndescription: 演示用的 skill\n---\n\n" + marker + "\n"
}

// skillRepoWithTwoVersions 造一个远端仓库：demo/SKILL.md 从 v1 变成 v2。
// 返回仓库路径与两个 commit。
func skillRepoWithTwoVersions(t *testing.T) (repo, first, second string) {
	t.Helper()
	repo = t.TempDir()
	runGit(t, repo, "init", "-q")
	skill := filepath.Join(repo, "demo")
	if err := os.MkdirAll(skill, 0o755); err != nil {
		t.Fatal(err)
	}
	writeSkillFile(t, skill, demoSkillFile("v1"))
	runGit(t, repo, "add", "-A")
	runGit(t, repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "v1")
	first = runGit(t, repo, "rev-parse", "HEAD")
	writeSkillFile(t, skill, demoSkillFile("v2"))
	runGit(t, repo, "add", "-A")
	runGit(t, repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "v2")
	second = runGit(t, repo, "rev-parse", "HEAD")
	return repo, first, second
}

// runGit 在 dir 里跑一条 git 命令，失败就把输出打出来。
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
