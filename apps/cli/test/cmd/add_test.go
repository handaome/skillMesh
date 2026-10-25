package cmd_test

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	cmd "github.com/handaome/skillmesh/apps/cli/internal/cmd"
	"github.com/handaome/skillmesh/apps/cli/internal/config"
	"github.com/handaome/skillmesh/apps/cli/internal/installer"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
)

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

// makeRepoTree 造一个仓库目录树，返回根路径。
func makeRepoTree(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	writeFile(t, filepath.Join(root, "skills", "alpha", "SKILL.md"), "# A\n")
	writeFile(t, filepath.Join(root, "skills", "beta", "SKILL.md"), "# B\n")
	writeFile(t, filepath.Join(root, "docs", "readme.md"), "不是 Skill")
	return root
}

func Test指定path时直接用该目录(t *testing.T) {
	root := makeRepoTree(t)
	candidates, err := cmd.LocateCandidates(root, "skills/alpha", false)
	if err != nil {
		t.Fatal(err)
	}
	if len(candidates) != 1 || filepath.Base(candidates[0].Dir) != "alpha" {
		t.Fatalf("选错了目录: %+v", candidates)
	}
}

func Test指定path不存在时报错(t *testing.T) {
	root := makeRepoTree(t)
	if _, err := cmd.LocateCandidates(root, "skills/nope", false); err == nil {
		t.Fatal("期望报子目录不存在")
	}
}

func Test唯一候选时自动选中(t *testing.T) {
	root := t.TempDir()
	writeFile(t, filepath.Join(root, "only-one", "SKILL.md"), "# X\n")

	candidates, err := cmd.LocateCandidates(root, "", false)
	if err != nil {
		t.Fatal(err)
	}
	if len(candidates) != 1 || filepath.Base(candidates[0].Dir) != "only-one" {
		t.Fatalf("选错了目录: %+v", candidates)
	}
}

func Test多个候选时要求显式指定或用all(t *testing.T) {
	root := makeRepoTree(t)
	_, err := cmd.LocateCandidates(root, "", false)
	if err == nil || !strings.Contains(err.Error(), "--path") {
		t.Fatalf("期望提示用 --path 指定，得到 %v", err)
	}
	if !strings.Contains(err.Error(), "--all") {
		t.Fatalf("错误信息应提示 --all: %v", err)
	}
	if !strings.Contains(err.Error(), "skills/alpha") || !strings.Contains(err.Error(), "skills/beta") {
		t.Fatalf("错误信息应列出候选目录: %v", err)
	}
}

func TestAll时返回全部候选(t *testing.T) {
	root := makeRepoTree(t)
	candidates, err := cmd.LocateCandidates(root, "", true)
	if err != nil {
		t.Fatal(err)
	}
	if len(candidates) != 2 {
		t.Fatalf("期望 2 个候选，得到 %d", len(candidates))
	}
}

func Test没有候选时报错(t *testing.T) {
	root := t.TempDir()
	writeFile(t, filepath.Join(root, "README.md"), "x")
	if _, err := cmd.LocateCandidates(root, "", true); err == nil {
		t.Fatal("期望报未找到 Skill")
	}
}

func Test目标不存在时放行(t *testing.T) {
	if err := cmd.CheckTarget(filepath.Join(t.TempDir(), "nope"), false); err != nil {
		t.Fatalf("目标不存在时应放行: %v", err)
	}
}

func Test同名且受管时放行(t *testing.T) {
	target := filepath.Join(t.TempDir(), "demo")
	if err := os.MkdirAll(target, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := installer.MarkInstalled(target, "https://example.com/x.git"); err != nil {
		t.Fatal(err)
	}
	if err := cmd.CheckTarget(target, false); err != nil {
		t.Fatalf("受管目录应允许重装: %v", err)
	}
}

func Test同名且不受管时拒绝除非replace(t *testing.T) {
	target := filepath.Join(t.TempDir(), "demo")
	writeFile(t, filepath.Join(target, "SKILL.md"), "# 手写的\n")

	err := cmd.CheckTarget(target, false)
	if err == nil || !strings.Contains(err.Error(), "--replace") {
		t.Fatalf("期望提示用 --replace，得到 %v", err)
	}
	if err := cmd.CheckTarget(target, true); err != nil {
		t.Fatalf("--replace 时应放行: %v", err)
	}
}

func TestRefType判定(t *testing.T) {
	cases := map[string]string{
		"":       "default",
		"main":   "branch",
		"v1.2.0": "branch",
		"1234567890123456789012345678901234567890": "commit",
	}
	for ref, want := range cases {
		if got := cmd.RefTypeOf(ref); got != want {
			t.Fatalf("ref %q 应判为 %s，实际 %s", ref, want, got)
		}
	}
}

// tag 与 branch 光看形状分不开，得问仓库。
// 设计文档 §7.1 定义了 branch/tag/commit 三种 ref_type。
func TestRefType能认出tag(t *testing.T) {
	repo := t.TempDir()
	runGit(t, repo, "init", "-q")
	write(t, filepath.Join(repo, "SKILL.md"), "# x\n")
	runGit(t, repo, "add", "-A")
	runGit(t, repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init")
	runGit(t, repo, "tag", "v1.2.0")

	if got := cmd.RefTypeOfInRepo(repo, "v1.2.0"); got != "tag" {
		t.Fatalf("仓库里存在的 tag 应判为 tag，实际 %s", got)
	}
	// 不是 tag 的名字还是 branch
	if got := cmd.RefTypeOfInRepo(repo, "main"); got != "branch" {
		t.Fatalf("普通名字应判为 branch，实际 %s", got)
	}
}

// --ref-type 显式指定时以用户说的为准。
func TestRefType显式指定优先(t *testing.T) {
	repo := t.TempDir()
	runGit(t, repo, "init", "-q")
	write(t, filepath.Join(repo, "SKILL.md"), "# x\n")
	runGit(t, repo, "add", "-A")
	runGit(t, repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init")
	runGit(t, repo, "tag", "v1.2.0")

	addCmd := cmd.NewAddCmd()
	if err := addCmd.Flags().Parse([]string{"--ref-type", "branch"}); err != nil {
		t.Fatal(err)
	}
	// 显式说是 branch 就是 branch，即使仓库里真有同名 tag
	if got := cmd.RefTypeOrDetect(repo, "v1.2.0", "branch"); got != "branch" {
		t.Fatalf("显式指定 branch 应优先，实际 %s", got)
	}
	// 不显式指定时才去探测
	if got := cmd.RefTypeOrDetect(repo, "v1.2.0", ""); got != "tag" {
		t.Fatalf("不指定时应探测出 tag，实际 %s", got)
	}
}

// --- PlanInstalls ---

func Test计划全部通过时都装(t *testing.T) {
	root := makeRepoTree(t)
	candidates, err := skillscan.Candidates(root)
	if err != nil {
		t.Fatal(err)
	}
	plan := cmd.PlanInstalls(root, candidates, t.TempDir(), false)

	if len(plan.Install) != 2 {
		t.Fatalf("期望装 2 个，得到 %d", len(plan.Install))
	}
	if len(plan.Skip) != 0 || len(plan.Fail) != 0 {
		t.Fatalf("不该有跳过或失败: %+v", plan)
	}
}

func Test单个Skill校验失败不影响其他(t *testing.T) {
	root := t.TempDir()
	writeFile(t, filepath.Join(root, "good", "SKILL.md"), "# G\n")
	// 坏目录：有 SKILL.md 但符号链接指向目录外
	bad := filepath.Join(root, "bad")
	writeFile(t, filepath.Join(bad, "SKILL.md"), "# B\n")
	outside := t.TempDir()
	if err := os.Symlink(outside, filepath.Join(bad, "escape")); err != nil {
		t.Skipf("当前环境不支持符号链接: %v", err)
	}

	candidates, err := skillscan.Candidates(root)
	if err != nil {
		t.Fatal(err)
	}
	plan := cmd.PlanInstalls(root, candidates, t.TempDir(), false)

	if len(plan.Install) != 1 || plan.Install[0].Name != "good" {
		t.Fatalf("应只装 good: %+v", plan.Install)
	}
	if len(plan.Fail) != 1 {
		t.Fatalf("应有 1 个失败: %+v", plan.Fail)
	}
	if !strings.Contains(plan.Fail[0].Why, "符号链接") {
		t.Fatalf("失败原因应提到符号链接: %s", plan.Fail[0].Why)
	}
}

func Test安装名冲突时跳过后者(t *testing.T) {
	root := t.TempDir()
	// 两个目录的 frontmatter 里 name 相同
	writeFile(t, filepath.Join(root, "a", "SKILL.md"), "---\nname: same\n---\n")
	writeFile(t, filepath.Join(root, "b", "SKILL.md"), "---\nname: same\n---\n")

	candidates, err := skillscan.Candidates(root)
	if err != nil {
		t.Fatal(err)
	}
	plan := cmd.PlanInstalls(root, candidates, t.TempDir(), false)

	if len(plan.Install) != 1 {
		t.Fatalf("应只装 1 个: %+v", plan.Install)
	}
	if len(plan.Skip) != 1 || !strings.Contains(plan.Skip[0].Why, "冲突") {
		t.Fatalf("应报告冲突: %+v", plan.Skip)
	}
}

func Test目标被占时跳过不覆盖(t *testing.T) {
	root := t.TempDir()
	writeFile(t, filepath.Join(root, "alpha", "SKILL.md"), "# A\n")
	base := t.TempDir()
	writeFile(t, filepath.Join(base, "alpha", "SKILL.md"), "# 手写的\n")

	candidates, err := skillscan.Candidates(root)
	if err != nil {
		t.Fatal(err)
	}
	plan := cmd.PlanInstalls(root, candidates, base, false)

	if len(plan.Install) != 0 {
		t.Fatalf("不该装: %+v", plan.Install)
	}
	if len(plan.Skip) != 1 || !strings.Contains(plan.Skip[0].Why, "--replace") {
		t.Fatalf("应报告需要 --replace: %+v", plan.Skip)
	}
	// 原有内容不能被动过
	data, err := os.ReadFile(filepath.Join(base, "alpha", "SKILL.md"))
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "# 手写的\n" {
		t.Fatalf("原有内容被改了: %q", string(data))
	}
}

// --agent all：装到所有已启用的适配器（详细设计 §3.5）。
func TestAddAgentAll装到所有启用的适配器(t *testing.T) {
	skillsA := t.TempDir()
	skillsB := t.TempDir()
	repo := localSkillRepo(t)
	stubClone(t, repo)

	projectRoot := t.TempDir()
	t.Chdir(projectRoot)

	original := cmd.Cfg
	cmd.Cfg.Agents = map[string]config.Agent{
		"claude-code": {Enabled: true, UserSkillsDir: skillsA},
		"codex":       {Enabled: true, UserSkillsDir: skillsB},
		"cursor":      {Enabled: false, UserSkillsDir: filepath.Join(t.TempDir(), "cursor")},
	}
	defer func() { cmd.Cfg = original }()

	addCmd := cmd.NewAddCmd()
	addCmd.SetArgs([]string{"https://github.com/x/y", "--path", "demo",
		"--agent", "all", "--scope", "user"})
	addCmd.SetOut(os.Stdout)
	addCmd.SetErr(os.Stderr)
	if err := addCmd.Execute(); err != nil {
		t.Fatal(err)
	}

	for _, dir := range []string{skillsA, skillsB} {
		if _, err := os.Stat(filepath.Join(dir, "demo", "SKILL.md")); err != nil {
			t.Fatalf("应装到 %s：%v", dir, err)
		}
	}
	saved, err := lockfile.Load(projectRoot)
	if err != nil {
		t.Fatal(err)
	}
	entry, ok := saved.Find("demo")
	if !ok {
		t.Fatalf("锁文件里应有 demo：%+v", saved.Skills)
	}
	if len(entry.Agents) != 2 {
		t.Fatalf("应记下两个 Agent，实际 %v", entry.Agents)
	}
}

// --- add 命令的行为 ---

// --install 现在是默认行为（设计文档 §7.4 的示例不带它）。
// 旧脚本写 --install 不该报错，--install=false 该仍然表示「不装」。
func TestInstall标记废弃但仍能解析(t *testing.T) {
	command := cmd.NewAddCmd()
	if err := command.Flags().Parse([]string{"--install"}); err != nil {
		t.Fatalf("--install 应仍可解析：%v", err)
	}
	if v, _ := command.Flags().GetBool("install"); !v {
		t.Fatal("--install 应为 true")
	}

	cmd2 := cmd.NewAddCmd()
	if err := cmd2.Flags().Parse([]string{"--install=false"}); err != nil {
		t.Fatalf("--install=false 应可解析：%v", err)
	}
	if v, _ := cmd2.Flags().GetBool("install"); v {
		t.Fatal("--install=false 应为 false")
	}

	cmd3 := cmd.NewAddCmd()
	if v, _ := cmd3.Flags().GetBool("install"); !v {
		t.Fatal("默认应是安装")
	}
}

// 不带 --install（也不带 --dry-run）时必须真的装进 Agent 目录并写锁文件——
// 之前这里打印「已登记」就返回，什么都没落盘（设计文档 §7.4 的示例照抄不生效）。
func TestAdd默认就安装(t *testing.T) {
	skillsDir := t.TempDir()
	repo := localSkillRepo(t)
	stubClone(t, repo)

	projectRoot := t.TempDir()
	t.Chdir(projectRoot)

	original := cmd.Cfg
	cmd.Cfg.Agents = map[string]config.Agent{
		"claude-code": {Enabled: true, UserSkillsDir: skillsDir},
	}
	defer func() { cmd.Cfg = original }()

	command := cmd.NewAddCmd()
	command.SetArgs([]string{"https://github.com/x/y", "--path", "demo", "--agent", "claude-code", "--scope", "user"})
	command.SetOut(os.Stdout)
	command.SetErr(os.Stderr)
	if err := command.Execute(); err != nil {
		t.Fatal(err)
	}

	target := filepath.Join(skillsDir, "demo")
	if _, err := os.Stat(filepath.Join(target, "SKILL.md")); err != nil {
		t.Fatalf("SKILL.md 应装到 %s：%v", target, err)
	}
	saved, err := lockfile.Load(projectRoot)
	if err != nil {
		t.Fatal(err)
	}
	entry, ok := saved.Find("demo")
	if !ok {
		t.Fatalf("锁文件里应有 demo：%+v", saved.Skills)
	}
	if entry.ResolvedCommit != "commit-from-stub" {
		t.Fatalf("锁文件里的 commit 应是 commit-from-stub，实际 %s", entry.ResolvedCommit)
	}
}

// --dry-run 只看不装：目录不碰、锁文件不写。
func TestAddDryRun不装也不写锁文件(t *testing.T) {
	skillsDir := t.TempDir()
	repo := localSkillRepo(t)
	stubClone(t, repo)

	projectRoot := t.TempDir()
	t.Chdir(projectRoot)

	original := cmd.Cfg
	cmd.Cfg.Agents = map[string]config.Agent{
		"claude-code": {Enabled: true, UserSkillsDir: skillsDir},
	}
	defer func() { cmd.Cfg = original }()

	command := cmd.NewAddCmd()
	command.SetArgs([]string{"https://github.com/x/y", "--path", "demo", "--agent", "claude-code", "--scope", "user", "--dry-run"})
	command.SetOut(os.Stdout)
	command.SetErr(os.Stderr)
	if err := command.Execute(); err != nil {
		t.Fatal(err)
	}

	if _, err := os.Stat(filepath.Join(skillsDir, "demo")); err == nil {
		t.Fatal("--dry-run 不该真的装")
	}
	if _, err := os.Stat(filepath.Join(projectRoot, "skillmesh.lock.yaml")); err == nil {
		t.Fatal("--dry-run 不该写锁文件")
	}
}

// 提示语不能前后矛盾：先说「将安装 N 个」又说「已登记」。
func TestAdd提示不再谎报已登记(t *testing.T) {
	skillsDir := t.TempDir()
	repo := localSkillRepo(t)
	stubClone(t, repo)

	projectRoot := t.TempDir()
	t.Chdir(projectRoot)

	original := cmd.Cfg
	cmd.Cfg.Agents = map[string]config.Agent{
		"claude-code": {Enabled: true, UserSkillsDir: skillsDir},
	}
	defer func() { cmd.Cfg = original }()

	command := cmd.NewAddCmd()
	command.SetArgs([]string{"https://github.com/x/y", "--path", "demo", "--agent", "claude-code", "--scope", "user", "--dry-run"})
	var out strings.Builder
	command.SetOut(&out)
	command.SetErr(&out)
	if err := command.Execute(); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(out.String(), "已登记") {
		t.Fatalf("不该再说「已登记」——那时什么都没写：%s", out.String())
	}
}

// stubClone 把 CloneRepo 换成本地目录拷贝：测试不联网。
// 真实的 git 克隆在 update_test.go 里测（那边本来就要跑 git）。
func stubClone(t *testing.T, source string) {
	t.Helper()
	original := cmd.CloneRepo
	cmd.CloneRepo = func(ctx context.Context, repo, ref, dest string) (string, error) {
		if err := copyTreeForTest(source, dest); err != nil {
			return "", err
		}
		return "commit-from-stub", nil
	}
	t.Cleanup(func() { cmd.CloneRepo = original })
}

// copyTreeForTest 把目录树复制到目标。
func copyTreeForTest(src, dst string) error {
	return filepath.Walk(src, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(src, path)
		if err != nil {
			return err
		}
		target := filepath.Join(dst, rel)
		if info.IsDir() {
			return os.MkdirAll(target, 0o755)
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		return os.WriteFile(target, data, 0o644)
	})
}

// localSkillRepo 造一个含 demo/SKILL.md 的本地目录树。
func localSkillRepo(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	writeFile(t, filepath.Join(root, "demo", "SKILL.md"), "# demo\n")
	return root
}
